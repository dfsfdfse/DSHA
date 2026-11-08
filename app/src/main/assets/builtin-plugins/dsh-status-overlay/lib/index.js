/**
 * dsh-status-overlay —— 把 agent 正在生成的内容实时送到手机屏幕顶部的悬浮条上，
 * 像音乐软件的歌词那样流式滚动；工具调用则显示成「正在执行命令」这类人话。
 *
 * 数据流：
 *   session/event → 这里节流合并 → GET 127.0.0.1:3090/app/overlay → App 侧 OverlayController
 *
 * 几个刻意的设计：
 *
 * 1) **不写模块级 `inject`**。模块级 inject 是硬依赖，缺服务就让 entry 永远 pending，
 *    dsh 会判定「1 entry did not activate」→ **整棵 plugin tree 加载失败**，Web 起不来。
 *    内置的 device-shell-guide 0.1.9 栽过这一次，三个用户的 Web 直接打不开。
 *    这个插件只用 ctx.on，不需要任何服务，那就一个都不声明。
 *
 * 2) **发「当前应显示的整行」而不是增量**。悬浮条只有一行、只显示尾部若干字，
 *    发整行是幂等的：丢一次包下一次就自动对上；发增量则一旦丢包就永久错位。
 *
 * 3) **节流 + 冷却**。流式 token 一秒几十个，每个都发一次 HTTP 纯属烧电，
 *    所以合并到 FLUSH_MS 一次；而用户没开这个功能（DISABLED）或没给悬浮窗权限
 *    （NO_PERMISSION）时进入长冷却，别对着一个关着的门一直敲。
 *
 * 4) **多实例**：dsh 可以同时跑多个会话，各自都在吐字。按 session 分桶、各带一个短标识
 *    交给 App 侧渲染，否则两路输出会交织成乱码。
 *
 * 5) **一个异常都不能冒出去**。这是个「顺带好看」的功能，绝不能影响 agent 干活 ——
 *    所有 IO 与解析都包在 try/catch 里，失败就静默降级。
 */
import { readFileSync, writeFileSync, renameSync, readdirSync, existsSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
export const name = 'dsh-status-overlay'

/** 桥地址（App 侧只监听回环，容器与宿主共享网络命名空间，所以直接连得上）。 */
const BRIDGE = 'http://127.0.0.1:3090/app/overlay'
/** 悬浮窗数据走文件：宿主可能同时装着正式版，3090 被它占着时 HTTP 推送会落错应用。 */
const FLOAT_STATE = '/root/.dsh/float-state.json'
/** 反向通道：宿主播下去用户要问 agent 的话，插件投递到会话。 */
const FLOAT_INBOX = '/root/.dsh/float-inbox.json'
/** 语音：宿主录音写 WAV(base64)，插件交给 dsh 的 speechToText 转写后回写结果。 */
const VOICE_IN = '/root/.dsh/float-voice.json'
const VOICE_OUT = '/root/.dsh/float-voice-result.json'
const WAKE_IN = '/root/.dsh/float-wake.json'
const WAKE_OUT = '/root/.dsh/float-wake-result.json'
/** 富内容悬浮窗：只发「重要信息 + 步骤」，不发原始 token。 */
const FLOAT = 'http://127.0.0.1:3090/app/float'
/** 会话管理工具的会话目录（删除时移到这里的回收站，不直接抹掉）。 */
const SESSIONS_ROOT = '/root/.dsh/sessions'
const SESSION_TRASH = '/root/.dsh/session-trash'
const TOKEN_PATH = '/root/.dsh/.bridge_token'
/** 合并窗口：120ms 一次，肉眼看起来仍是连续流动的。 */
const FLUSH_MS = 120
/** 插件侧只做粗截断（防 URL 编码后过长），真正的行数由 App 侧按用户配置决定 ——
 *  插件不知道用户把「最多显示几行」调成了几。上限给得比显示所需大得多是故意的：
 *  App 那边按显示宽度切行，切分点只取决于文本开头，开头一变所有行就重排。 */
const MAX_CHARS = 1200
/** 超限时一次砍到这个长度，而不是逐字砍 —— 见 tail() 的注释。 */
const KEEP_CHARS = 800
/** 功能关闭 / 没权限时的冷却时长。 */
const COOLDOWN_MS = 60_000
/** 思考过程被 App 拒掉后隔多久再试一次 —— 那个开关随时可能被打开，不能一拒就永久闭嘴。 */
const REASONING_RETRY_MS = 60_000
/** 单次请求超时：桥就在本机，1.5 秒都不该等。 */
const TIMEOUT_MS = 1500

/** 工具名 → 人话。命中越具体的放前面；没命中的兜底成「正在使用 X」。 */
const TOOL_LABELS = [
  [/bash|shell|command|exec|terminal/i, '⚙ 正在执行命令'],
  [/write|create.*file|edit|patch|apply/i, '⚙ 正在修改文件'],
  [/read|cat|view|open.*file/i, '⚙ 正在读取文件'],
  [/glob|grep|search|find/i, '⚙ 正在搜索'],
  [/fetch|web|http|browse|url/i, '⚙ 正在联网查资料'],
  [/todo|plan/i, '⚙ 正在整理任务清单'],
  [/task|agent|subagent|dispatch/i, '⚙ 正在派子任务'],
  [/image|screenshot|vision/i, '⚙ 正在看图'],
  [/notify|toast|share|clip/i, '⚙ 正在调用手机功能'],
]

function toolLabel(name) {
  const n = String(name || '').trim()
  if (!n) return '⚙ 正在使用工具'
  for (const [re, label] of TOOL_LABELS) {
    if (re.test(n)) return label
  }
  return '⚙ 正在使用 ' + n
}

/** 工具参数里挑一个「值得显示的那个」——命令原文最有用，其次是路径/模式/URL。
 *  arguments 是 JSON 字符串；模型也可能吐出半截的非法 JSON，所以解析失败就放弃。 */
const ARG_KEYS = ['command', 'cmd', 'script', 'path', 'file_path', 'filePath',
  'pattern', 'query', 'url', 'prompt', 'description']

function toolDetail(argsJson) {
  try {
    if (!argsJson) return ''
    const o = typeof argsJson === 'string' ? JSON.parse(argsJson) : argsJson
    if (!o || typeof o !== 'object') return ''
    for (const k of ARG_KEYS) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
    // 没命中已知键名 → 退而取第一个非空字符串值（新工具也能显示点东西）
    for (const v of Object.values(o)) {
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
    return ''
  } catch {
    return ''
  }
}

let token
let tokenReadAt = 0
function bridgeToken() {
  if (token !== undefined && Date.now() - tokenReadAt < 5000) return token
  tokenReadAt = Date.now()
  try {
    token = readFileSync(TOKEN_PATH, 'utf8').trim()
  } catch {
    token = ''    // 桥没起来 / 桌面环境：整个功能静默停用
  }
  return token
}

/** sessionKey → { line, timer, dirty } */
const state = new Map()
let cooldownUntil = 0
/** App 侧回了 SKIP_REASONING（用户当时不想看思考过程）→ 暂停这一类到这个时间点。
 *  **带时效而不是永久**：开关是随时可改的，写成永久的话用户之后在配置页打开
 *  「显示思考过程」，插件仍然一条都不发，非得重启 dsh 才恢复 —— 真机上报的
 *  「思考内容无法显示」就是这个。 */
let skipReasoningUntil = 0

/** 子会话（subagent）归到父会话的桶里，返回 {key, depth}。 */
function nestOf(ownKey, session) {
  try {
    const parentId = String(session?.header?.parentSession || '')
    if (!parentId) return { key: ownKey, depth: 0 }
    for (const [k] of state) {
      if (fullIdOf(k) === parentId) return { key: k, depth: 1 }
    }
  } catch { }
  return { key: ownKey, depth: 0 }
}

function sessionKey(session) {
  try {
    const raw = session?.id ?? session?.sessionId ?? session?.key ?? ''
    const s = String(raw)
    return s ? s.slice(-8) : '-'
  } catch {
    return '-'
  }
}

/** 压成单行并限长。
 *
 *  **不能逐字截**：App 侧按显示宽度把这段文本切成固定的行、再取最后几行，切分点只取决于
 *  文本开头。每来一个字就把开头砍掉一个字的话，所有行都会重排 —— 那正是用户报的
 *  「最前端文字不停滚走、可读性很差」。所以超限时一次砍到 KEEP_CHARS，并尽量从词边界
 *  开始，把重排频率从「每个字一次」降到「几百个字一次」。 */
function tail(s) {
  const one = String(s || '').replace(/\s+/g, ' ').trim()
  if (one.length <= MAX_CHARS) return one
  let from = one.length - KEEP_CHARS
  const sp = one.indexOf(' ', from)
  if (sp > 0 && sp - from < 40) from = sp + 1
  return one.slice(from)
}

/** 从桥的响应里取出真正的结果字符串。
 *
 *  **桥返回的是 JSON**：`{"result":"OK"}`（HttpShellService 统一包装，注释里专门写过
 *  「result 必须包引号，否则是非法 JSON」）。这个插件原来直接把整段 body 拿去和
 *  'DISABLED' 做等值比较 —— 永远不成立。
 *
 *  代价是**冷却机制从来没生效过**：用户没开悬浮条、或者没给悬浮窗权限时，插件照样
 *  每轮发一次 HTTP，一路发到会话结束。不崩、不报错、日志里也看不出来，只是白烧电。
 *  reportPluginStates 的 `=== 'OK'` 同理 —— previous 永远设不上，状态没变化也重报。
 *
 *  解析失败时原样返回：将来桥要是改成纯文本，这里不用跟着改。 */
function unwrap(raw) {
  const s = (raw || '').trim()
  if (!s) return ''
  try {
    const j = JSON.parse(s)
    if (j && typeof j.result === 'string') return j.result.trim()
  } catch {
    // 不是 JSON —— 当纯文本用
  }
  return s
}

/** sessionKey → 该会话的标题（来自 session/title 事件），只用于悬浮条的多任务标识行。 */
const titles = new Map()

function titleOf(key, session) {
  try {
    const known = titles.get(key)
    if (known) return known
    const raw = session?.title ?? session?.name ?? ''
    return String(raw || '').replace(/\s+/g, ' ').trim().slice(0, 24)
  } catch {
    return ''
  }
}

async function send(key, kind, text) {
  const tok = bridgeToken()
  if (!tok) return
  if (Date.now() < cooldownUntil) return
  const url = `${BRIDGE}?kind=${encodeURIComponent(kind)}`
    + `&session=${encodeURIComponent(key)}`
    + `&title=${encodeURIComponent(titles.get(key) || '')}`
    + `&text=${encodeURIComponent(text || '')}`
  try {
    const res = await fetch(url, {
      headers: { 'X-Token': tok },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    const body = unwrap(await res.text())
    if (body === 'DISABLED' || body === 'NO_PERMISSION') {
      // 用户没开这个功能或没授权 —— 进冷却，别一直敲一扇关着的门
      cooldownUntil = Date.now() + COOLDOWN_MS
    } else if (body === 'SKIP_REASONING') {
      // 功能开着，只是这会儿不看思考过程：别整段冷却，只停这一类，而且**要带时效**
      skipReasoningUntil = Date.now() + REASONING_RETRY_MS
    }
  } catch {
    // 桥没起、超时、被拒：这功能不重要，静默降级
    cooldownUntil = Date.now() + 5000
  }
}

/** 把会话的「状态 + 步骤」发给富内容悬浮窗；80ms 合帧（流式够跟手，又不至于逐 token 写盘）。 */
function scheduleFloat(key, b) {
  if (b.ftimer) return
  b.ftimer = setTimeout(() => {
    b.ftimer = undefined
    void sendFloat(key, b)
  }, 80)
  if (typeof b.ftimer?.unref === 'function') b.ftimer.unref()
}

async function sendFloat(key, b) {
  const tok = bridgeToken()
  if (!tok) return
  // 汇总所有在跑的会话，一次发给悬浮窗；页面用标签切换查看。
  const tasks = []
  for (const [k, s] of state) {
    if (!s || !(s.messages || []).length) continue
    const usage = usageOf(s.session)
    tasks.push({
      id: fullIdOf(k) || k,
      title: titles.get(k) || '',
      messages: (s.messages || []).slice(-20),
      updated: s.updated || 0,
      done: !!s.done,
      usage,
      model: modelOf(k),
      permission: permissionOf(s.session),
      goal: goalOf(s.session),
      todos: todosOf(s.session),
      commands: commandsOf(s.session),
      since: s.turnStart || 0,
      stats: {
        turns: s.turns || 0,
        steps: Number(s.stepCount) || 0,
        total: usage?.total || 0,
        cacheHit: usage?.cacheHit ?? null,
        tps: s.genMs > 0 && s.outTokens > 0 ? Math.round(s.outTokens / (s.genMs / 1000)) : 0,
      },
    })
  }
  tasks.sort((left, right) => right.updated - left.updated)
  const payload = JSON.stringify({
    title: tasks.length ? tasks[0].title : '',
    tasks: tasks.slice(0, 6),
    active: fullIdOf(key) || key,
    count: tasks.length,
    models: modelCatalog.slice(0, 16),
    threads: threadList.slice(0, 24),
    files: filesResult,
    notice,
  })
  writeState(payload)
  // 只写文件：HTTP 端点会去创建窗口，导致 dsh 每回复一次就弹窗。

}

let projections
let agentsApi
let llmApi
let modelCatalog = []
/** 上一次提示文案：投递失败之类的原因要送到页面，不能点了没反应。 */
let notice = ''
/** 模型清单拉取节流（拿不到时别每帧都问一遍）。 */
let modelTryAt = 0

/** 会话的完整 id。状态文件里给页面用的必须是完整 id ——
 *  短 key 只是插件内部的桶名，拿它去 agents.get() 永远查不到（发送就"消失"了）。 */
function fullIdOf(key) {
  try {
    const s = state.get(key)
    const raw = s?.session?.id ?? s?.session?.sessionId ?? ''
    const id = String(raw)
    return id && id !== 'undefined' ? id : ''
  } catch {
    return ''
  }
}

/** 按「完整 id → 短 key → 最近活跃会话」的顺序找出真正可投递的 agent。
 *  页面可能拿着旧 id、空 id，或者还没收到状态；这三种都要能落到一个活会话上。 */
function liveAgent(agents, wanted) {
  if (!agents) return undefined
  const w = String(wanted || '').trim()
  if (w) {
    try {
      const direct = agents.get(w)
      if (direct) return direct
    } catch { }
    for (const [k, s] of state) {
      const full = fullIdOf(k)
      if (!full) continue
      if (k !== w && full !== w && !full.endsWith(w)) continue
      try {
        const hit = agents.get(full)
        if (hit) return hit
      } catch { }
    }
  }
  let best
  for (const [, s] of state) {
    if (!s?.session) continue
    if (!best || (s.updated || 0) > (best.updated || 0)) best = s
  }
  if (!best) return undefined
  try {
    const id = String(best.session.id ?? best.session.sessionId ?? '')
    return id ? agents.get(id) : undefined
  } catch {
    return undefined
  }
}

let fileRefApi
let commandsApi
/** @ 引用补全的最近一次结果（页面按 query 对齐）。 */
let filesResult = null
let goalsApi
let permissionApi
let defaultModelApi
let queryApi
/** 全部会话（含没有消息的）：页面靠它列出所有对话。 */
let threadList = []
/** 最近一次见到的会话工作目录，新建会话时沿用。 */
let lastCwdPath = ''

function newSessionId() {
  try {
    if (globalThis.crypto?.randomUUID) return 'session-' + globalThis.crypto.randomUUID()
  } catch { }
  return 'session-' + Date.now().toString(36) + '-' + Math.random().toString(16).slice(2, 10)
}

let threadsAt = 0
function maybeRefreshThreads() {
  if (Date.now() - threadsAt < 5000) return
  threadsAt = Date.now()
  void refreshThreads()
}

/** 拉一遍全部会话（持久化的 + 活着的），带上标题。 */
async function refreshThreads() {
  if (!queryApi) return
  try {
    const records = await queryApi.listSessions()
    const rows = []
    const ids = []
    for (const record of records) {
      const id = String(record?.header?.id || '')
      if (!id || ids.includes(id)) continue
      const cwd = String(record?.header?.cwd || '')
      if (cwd) lastCwdPath = cwd
      ids.push(id)
      rows.push({
        id,
        cwd,
        live: !!record.live,
        updated: Number(record?.header?.updatedAt || record?.header?.createdAt) || 0,
        title: '',
      })
      if (ids.length >= 24) break
    }
    if (ids.length) {
      try {
        const titleResults = await queryApi.readTitleSnapshots(ids)
        ids.forEach((id, i) => {
          const result = titleResults?.[i]
          const value = result && result.status === 'fulfilled' ? result.value : undefined
          if (!value) return
          // foldSessionTitle 返回的是快照对象（{title, messageSeqs, source, eventSeq…}），
          // 直接 String() 会变成 [object Object]。
          const snapshot = value.title
          const text = typeof snapshot === 'string'
            ? snapshot
            : String(snapshot?.title || '')
          const title = text.trim()
          if (title) rows[i].title = title.slice(0, 40)
        })
      } catch { /* 标题拿不到就只显示目录 */ }
    }
    // 已经有消息的会话标题以本地捕获的为准（更及时）
    for (const row of rows) {
      const known = titles.get(String(row.id).slice(-8))
      if (known) row.title = known
    }
    rows.sort((a, b) => (b.updated || 0) - (a.updated || 0))
    threadList = rows
    publishFloat('')
  } catch { /* 会话清单不是关键路径 */ }
}

/** 从持久化日志里折出历史消息：用户、回复、工具调用（只取最近一段）。 */
async function loadHistory(sessionId) {
  if (!queryApi || !sessionId) return false
  try {
    const loaded = await queryApi.readSession(sessionId)
    const events = Array.isArray(loaded?.events) ? loaded.events.slice(-260) : []
    const key = sessionId.slice(-8)
    const b = bucket(key)
    b.session = { id: sessionId }
    b.messages = []
    const textOf = content => {
      if (!Array.isArray(content)) return ''
      return content.filter(p => p && p.type === 'text').map(p => p.text).join('').trim()
    }
    for (const event of events) {
      const data = event?.data || {}
      if (event.type === 'user/message') {
        const sourceKind = String(data?.source?.kind || data?.message?.source?.kind || '')
        if (sourceKind && sourceKind !== 'user') continue
        const text = textOf(data.message?.content || data.content)
        if (text) b.messages.push({ depth: 0, kind: 'user', title: text.slice(0, 120), text: text.slice(0, 4000), at: Number(event.at) || 0 })
      } else if (event.type === 'assistant/message') {
        const text = textOf(data.message?.content || data.content)
        if (text) b.messages.push({ depth: 0, kind: 'assistant', title: text.slice(0, 48), text: text.slice(0, 4000), at: Number(event.at) || 0 })
      } else if (event.type === 'tool/call') {
        const label = toolLabel(data.name)
        const detail = toolDetail(data.arguments)
        b.messages.push({ depth: 0, kind: 'tool', title: label, text: detail || '', at: Number(event.at) || 0 })
      }
    }
    b.messages = b.messages.slice(-40)
    b.done = true
    b.updated = Date.now()
    if (!b.turns) b.turns = b.messages.filter(m => m.kind === 'user').length
    if (!b.stepCount) b.stepCount = b.messages.filter(m => m.kind === 'tool').length
    publishFloat(sessionId)
    return true
  } catch {
    return false
  }
}

/** 当前会话可用的 / 指令（commands.list 的 descriptor）。 */
function commandsOf(session) {
  if (!commandsApi || !session || !agentsApi) return []
  try {
    const agent = agentsApi.get(String(session.id || ''))
    if (!agent) return []
    const list = commandsApi.list(agent) || []
    return list.slice(0, 40).map(c => ({ n: String(c.name), d: String(c.description || '') }))
  } catch {
    return []
  }
}

/**
 * 会话当前的目标（dsh GoalBar 的数据源）：phase / objective / revision。
 * 只读；暂停、恢复、清除都走 goals 服务的过渡方法（带 revision 校验）。
 */
function goalOf(session) {
  if (!goalsApi || !session || !agentsApi) return null
  try {
    const agent = agentsApi.get(String(session.id || ''))
    if (!agent) return null
    const view = goalsApi.get(agent)
    if (!view || view.phase === 'complete') return null
    return {
      id: String(view.id),
      phase: String(view.phase),
      objective: String(view.objective || '').slice(0, 200),
      revision: Number(view.revision) || 0,
      activation: String(view.activation || ''),
    }
  } catch {
    return null
  }
}

/**
 * 会话当前的任务清单（todo_write 的 `todos` 投影）：[{content, status}]。
 * 每轮开始会清空，所以它就是「这一轮准备做的事」。没写过时是 null。
 */
function todosOf(session) {
  if (!projections || !session) return null
  try {
    const snap = projections.snapshot(session, ['todos'])
    const list = snap?.values?.todos
    if (!Array.isArray(list) || !list.length) return null
    return list.slice(0, 40).map(item => ({
      content: String(item?.content || '').slice(0, 200),
      status: String(item?.status || 'pending'),
    }))
  } catch {
    return null
  }
}

/**
 * 会话当前的权限预设（dsh 的 permissions 投影）。
 * 只读 current/names，真正的写入走 /permission 命令，和 Web 客户端是同一条路。
 */
function permissionOf(session) {
  if (!permissionApi || !session) return null
  try {
    const current = permissionApi.current(session)
    if (!current) return null
    const names = Array.from(permissionApi.names || []).filter(n => typeof n === 'string')
    return { current: String(current), options: names.slice(0, 8) }
  } catch {
    return null
  }
}

/** 找出能投递的 agent；没有就新建一个会话（用户要求：dsh 起着就能聊）。 */
async function ensureAgent(wanted) {
  if (!agentsApi) return undefined
  const id = String(wanted || '').trim()
  // 悬浮窗的「＋ 新会话」：不复用任何现有会话，直接新建
  const fresh = id === 'new'
  const live = fresh ? undefined : liveAgent(agentsApi, id)
  if (live) return live
  const modelSelection = (() => {
    try { return defaultModelApi?.currentSelection?.() } catch { return undefined }
  })()
  const agentOptions = {}
  if (modelSelection?.provider) agentOptions.provider = String(modelSelection.provider)
  if (modelSelection?.model) agentOptions.model = String(modelSelection.model)
  // 先试着恢复指定的老会话，再退到新建
  if (id && !fresh) {
    try {
      const resumed = await agentsApi.resume({ resumeSessionId: id, agentOptions })
      if (resumed?.agent) return resumed.agent
    } catch { /* 恢复不了就新建 */ }
  }
  const cwd = lastCwdPath || '/root'
  try {
    const created = await agentsApi.create({
      sessionId: newSessionId(),
      agentOptions,
      meta: { cwd },
    })
    if (created?.agent) return created.agent
  } catch (error) {
    try {
      const created = await agentsApi.create({ sessionId: newSessionId(), agentOptions })
      if (created?.agent) return created.agent
    } catch {
      throw error
    }
  }
  return undefined
}

/**
 * 会话清单：优先用已经维护的 threadList，空的时候现拉一次。
 * @returns {Promise<Array<{id:string,title:string,updated:number,live:boolean}>>}
 */
async function sessionRows() {
  if (!threadList.length) await refreshThreads()
  return threadList.slice(0, 40)
}

/** 会话 id → 磁盘上的会话目录（可能不存在）。 */
function sessionDirOf(id) {
  const target = 'session-' + String(id || '')
  try {
    for (const name of readdirSync(SESSIONS_ROOT)) {
      const dir = join(SESSIONS_ROOT, name, target)
      if (existsSync(dir)) return dir
    }
  } catch { /* 目录结构不认识就当没找到 */ }
  return ''
}

/** 让 App 切到指定会话（''=跟随最近活跃，'new'=新会话草稿）。 */
async function openInApp(session) {
  const tok = bridgeToken()
  if (!tok) return false
  const url = 'http://127.0.0.1:3090/app/floatwin?panel=chat&session=' + encodeURIComponent(session || '')
  try {
    const res = await fetch(url, { headers: { 'X-Token': tok }, signal: AbortSignal.timeout(4000) })
    const body = (await res.text()).trim()
    return body.startsWith('OK')
  } catch {
    return false
  }
}

/** 中文/阿拉伯数字 → 整数；取不到返回 0。 */
function numberOf(text) {
  const arabic = String(text).match(/\d+/)
  if (arabic) return Number(arabic[0])
  const cn = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 }
  const hit = String(text).match(/[一二两三四五六七八九十]/)
  return hit ? (cn[hit[0]] || 0) : 0
}

/**
 * 把用户嘴里说的「那个会话」解析成真实 id：完整 id、id 前缀、「最近/最新」、「倒数第 N 个」、
 * 标题或 id 片段都认。语音转写常把「会话」写成「回话/绘画」之类，那是技能层的活，这里只管解析。
 */
function resolveSession(wanted, rows) {
  const w = String(wanted || '').trim()
  if (!w) return ''
  const list = [...rows].sort((a, b) => (b.updated || 0) - (a.updated || 0))
  if (!list.length) return ''
  if (list.some(r => r.id === w)) return w
  const byPrefix = list.find(r => r.id.startsWith(w))
  if (byPrefix) return byPrefix.id
  if (/最近|最新|latest|上一个|刚才|刚刚/.test(w)) return list[0].id
  if (/倒数|第/.test(w)) {
    const n = numberOf(w)
    if (n >= 1 && n <= list.length) return list[n - 1].id
  }
  const byTitle = list.find(r => String(r.title || '').includes(w))
  return byTitle ? byTitle.id : ''
}

/** 会话管理：语音说「删掉最近的会话」「打开昨天那个」「新建会话」都走这里。 */
const SESSION_ACTIONS = ['list', 'read', 'open', 'new', 'delete']

async function sessionAdmin(args) {
  const action = String(args?.action || '')
  const wanted = String(args?.session || '').trim()
  if (action === 'list') {
    const rows = await sessionRows()
    const q = String(args?.query || '').trim().toLowerCase()
    const picked = q
      ? rows.filter(r => String(r.title || '').toLowerCase().includes(q) || r.id.startsWith(q))
      : rows
    const limit = Math.max(1, Math.min(40, Number(args?.limit) || 10))
    if (!picked.length) return { ok: true, count: 0, text: '没有匹配的会话。' }
    const lines = picked.slice(0, limit).map((r, i) => {
      const when = r.updated ? new Date(r.updated).toLocaleString('zh-CN') : ''
      return `${i + 1}. ${r.title || '(未命名)'} · ${r.id.slice(-8)} · ${when}${r.live ? ' · 进行中' : ''}`
    })
    return { ok: true, count: picked.length, text: `共 ${picked.length} 个会话：\n` + lines.join('\n') }
  }
  if (action === 'read') {
    if (!wanted) return { ok: false, count: 0, text: '需要 session（会话 id）' }
    if (!queryApi) return { ok: false, count: 0, text: '会话查询服务不可用' }
    const rows0 = await sessionRows()
    const id0 = resolveSession(wanted, rows0)
    if (!id0) return { ok: false, count: 0, text: `没找到对应「${wanted}」的会话，先 list 看一下` }
    const loaded = await queryApi.readSession(id0)
    const events = Array.isArray(loaded?.events) ? loaded.events.slice(-260) : []
    const rows = []
    for (const event of events) {
      const data = event?.data || {}
      const textOf = content => Array.isArray(content)
        ? content.filter(p => p && p.type === 'text').map(p => p.text).join('').trim()
        : ''
      if (event.type === 'user/message') {
        const kind = String(data?.source?.kind || data?.message?.source?.kind || '')
        if (kind && kind !== 'user') continue
        const text = textOf(data.message?.content || data.content)
        if (text) rows.push({ role: '用户', text: text.slice(0, 800) })
      } else if (event.type === 'assistant/message') {
        const text = textOf(data.message?.content || data.content)
        if (text) rows.push({ role: '助手', text: text.slice(0, 800) })
      }
    }
    const tail = rows.slice(-(Math.max(1, Math.min(40, Number(args?.limit) || 12))))
    if (!tail.length) return { ok: true, count: 0, text: '这个会话没有可读的对话记录。' }
    const title0 = (rows0.find(r => r.id === id0) || {}).title || id0.slice(-8)
    return {
      ok: true,
      count: tail.length,
      text: `会话「${title0}」最近 ${tail.length} 条：\n` + tail.map(r => `【${r.role}】${r.text}`).join('\n'),
    }
  }
  if (action === 'open') {
    if (!wanted) return { ok: false, count: 0, text: '需要 session（会话 id）；可以先 list 查 id' }
    if (wanted === 'new') return sessionAdmin({ action: 'new' })
    const rows = await sessionRows()
    const id = resolveSession(wanted, rows)
    if (!id) return { ok: false, count: 0, text: `没找到对应「${wanted}」的会话，先 list 看一下` }
    const hit = rows.find(r => r.id === id)
    const ok = await openInApp(id)
    return ok
      ? { ok: true, count: 1, text: `已打开会话「${hit ? (hit.title || id.slice(-8)) : id.slice(-8)}」` }
      : { ok: false, count: 0, text: '打开失败：悬浮窗没开或没有悬浮窗权限' }
  }
  if (action === 'new') {
    const ok = await openInApp('new')
    return ok
      ? { ok: true, count: 1, text: '已打开悬浮窗的新会话，接着说的话就是新会话的第一句' }
      : { ok: false, count: 0, text: '打开失败：悬浮窗没开或没有悬浮窗权限' }
  }
  if (action === 'delete') {
    if (!wanted) return { ok: false, count: 0, text: '需要 session（会话 id）；可以先用 list 查' }
    {
      const rowsD = await sessionRows()
      const resolved = resolveSession(wanted, rowsD)
      if (resolved && resolved !== wanted) {
        const hit = rowsD.find(r => r.id === resolved)
        return {
          ok: false,
          count: 0,
          text: `「${wanted}」指向会话「${hit ? (hit.title || resolved.slice(-8)) : resolved.slice(-8)}」（${resolved}）。`
            + '删除请用这个完整 session 再调一次并带 confirm:true；先向用户复述标题让其确认。',
        }
      }
    }
    if (args?.confirm !== true) {
      return { ok: false, count: 0, text: `要删除会话 ${wanted} 需要把 confirm 设为 true；删除会移进回收站，可人工恢复` }
    }
    if (agentsApi && agentsApi.get && agentsApi.get(wanted)) {
      return { ok: false, count: 0, text: '这个会话正在运行，先停止再删' }
    }
    const dir = sessionDirOf(wanted)
    if (!dir) return { ok: false, count: 0, text: `找不到会话 ${wanted} 的存储目录` }
    try {
      mkdirSync(SESSION_TRASH, { recursive: true })
      const dest = join(SESSION_TRASH, `${Date.now()}-session-${wanted}`)
      renameSync(dir, dest)
      threadList = threadList.filter(r => r.id !== wanted)
      try { state.delete(String(wanted).slice(-8)) } catch { }
      publishFloat('')
      return { ok: true, count: 1, text: `已删除会话 ${wanted}（已移到 ${SESSION_TRASH}，需要的话可以恢复）` }
    } catch (error) {
      return { ok: false, count: 0, text: '删除失败：' + String(error?.message || error).slice(0, 120) }
    }
  }
  return { ok: false, count: 0, text: `不认识的动作 ${action}；可用：${SESSION_ACTIONS.join(' / ')}` }
}

/** 注册诊断：注册结果写一行，工具没出现时能直接看出是哪一步失败。 */
function pluginDiag(what, detail) {
  try {
    writeFileSync('/root/.dsh/float-plugin.log',
      `${new Date().toISOString()} ${what} ${detail}\n`, { flag: 'a', mode: 0o600 })
  } catch { /* 诊断写不进去不影响功能 */ }
}

/** 工具面：给模型一个稳定的入口，而不是让它自己拼 shell 或猜文件路径。 */
function registerSessionTool(ctx) {
  const disposers = []
  if (!ctx || !ctx.tools || typeof ctx.tools.register !== 'function') {
    pluginDiag('tool', 'skip: ctx.tools 不可用')
    return () => {}
  }
  try {
    disposers.push(ctx.tools.register({
      name: 'rcha_session_admin',
      description: [
        '管理 RCHA/dsh 的会话与聊天记录（用户常用语音提出这类请求）。',
        'list：列出会话（可按 query 过滤标题或 id 前缀），返回标题、id 尾部、时间；',
        'read：读某个会话最近的对话记录（session 传 id，uuid 尾部也能识别）；',
        'open：在悬浮窗里打开指定会话，用户说「打开某某会话」时用这个；',
        'new：打开一个新会话草稿，用户说「新建会话」时用这个；',
        'delete：删除会话（必须 confirm:true，会先移进回收站，不是不可恢复）。',
        '调用前先用 list 拿到准确的 id，不要臆造。',
      ].join(' '),
      parameters: {
        type: 'object',
        additionalProperties: false,
        required: ['action'],
        properties: {
          action: { type: 'string', enum: [...SESSION_ACTIONS], description: 'list | read | open | new | delete' },
          session: { type: 'string', description: '会话 id（open/read/delete 必填）；uuid 尾部或标题片段也接受' },
          query: { type: 'string', description: 'list 时按标题或 id 前缀过滤' },
          limit: { type: 'integer', description: 'list 返回条数（默认 10）、read 返回轮数（默认 12）' },
          confirm: { type: 'boolean', description: 'delete 时必须为 true 才会真正删除' },
        },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          required: ['ok', 'count', 'text'],
          properties: {
            ok: { type: 'boolean' },
            count: { type: 'integer' },
            text: { type: 'string' },
          },
        },
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      async execute(args) {
        return sessionAdmin(args)
      },
    }))
    pluginDiag('tool', 'ok rcha_session_admin')
  } catch (error) {
    pluginDiag('tool', 'fail ' + String(error?.message || error).slice(0, 200))
  }
  return () => {
    for (const dispose of disposers) {
      try { dispose() } catch { }
    }
  }
}

/** 运行时技能：把「用语音管会话」这件事写清楚，模型自己决定什么时候用。 */
function registerSessionSkill(ctx) {
  try {
    return ctx.skills.register({
      name: 'rcha-voice-sessions',
      description: '用语音（或文字）查询、打开、新建、删除 RCHA 的会话与聊天记录。',
      source: 'builtin:dsh-status-overlay/rcha-voice-sessions',
      content: [
        '# 语音/文字管理会话',
        '',
        '用户的输入多半来自悬浮窗语音转写，错字很多。先按意图理解，再调用 `rcha_session_admin`。',
        '',
        '## 一、同义与同音对照（出现左边任一写法，都按右边的意思处理）',
        '',
        '**重要：这个软件里「会话」和「聊天记录」是同一件事。** 用户嘴里说会话、聊天记录、记录、历史、',
        '对话、谈话、聊过的东西，指的都是同一套能力（`rcha_session_admin`），没有第二套工具，也不用追问到底是哪个。',
        '',
        '| 真实意图 | 转写里可能长这样 |',
        '| --- | --- |',
        '| 会话（= 聊天记录 = 历史对话） | 聊天记录、聊天纪绿、聊田记录、缭天记录、记录、历史、对话、谈话、回话、绘画、绘话、会画、汇话、灰话、惠话、会社、灰化、慧话、话题 |',
        '| 新建 / 新增 | 心建、欣建、新见、心开、重开 |',
        '| 打开 | 打尔、打看、大凯、达开、跳转 |',
        '| 删除 | 扇除、山除、删出、衫除、珊除、干掉、清掉 |',
        '| 查询 / 查看 | 查寻、插询、质询、看看、念念 |',
        '| 最近 | 罪近、最进、刚才、刚刚、上一个 |',
        '| 倒数第三 / 第二个 | 倒数第散、到数第儿、第两个 |',
        '',
        '只要句子里出现上表的错字，且上下文是在说对话/聊天/记录，就当作用户在说「会话」，不要反问「绘画是什么意思」，',
        '也不要问「你是指会话还是聊天记录」——它们是同一件事，直接按会话能力处理。',
        '',
        '## 二、意图 → 动作',
        '',
        '- 「有哪些回话 / 最近的绘画 / 我的聊天记录」→ `{action:"list"}`；要看内容再说 `{action:"read", session:"<id>"}`。',
        '- 「把昨天那个绘话的记录念给我听」→ `{action:"read", session:"最近"}`（或先 list 定位再 read）。',
        '- 「打开那个关于 X 的绘话」→ 先 `list` 找到 id，再 `{action:"open", session:"<id 或标题片段>"}`。',
        '- 「打开最近那个 / 倒数第二个回话」→ `{action:"open", session:"最近"}` 或 `session:"倒数第2个"`，工具会自己按时间排序解析。',
        '- 「开个新的 / 心建一个」→ `{action:"new"}`。',
        '- 「把最近那个删了」→ 先用 `session:"最近"` 调 `delete`，工具会回一条「指向哪个会话」，把标题和时间复述给用户，得到确认后再带完整 id 和 `confirm:true` 调一次。',
        '',
        '## 三、硬规则',
        '',
        '1. id 只从 `list` 结果或工具返回里取，不要编；`list` 里显示的是 uuid 尾号，取尾部也能用。',
        '2. 删除必须先复述对象、等用户明确同意；一次只删一个；正在运行的会话不删。',
        '3. 用户只是在正常聊天（「帮我写个函数」「这个 bug 怎么修」）时，不要调用这些动作。',
        '4. 转写明显残缺（比如只剩「那个」）时，先 `list` 再问一句「你说的是不是 X」，不要瞎猜着开或删。',
        '5. 做完用一句口语中文回报，例如「打开了「修 git push 失败」那个会话」；不要贴 JSON、不要贴 uuid。',
        '6. 用户说的数字口语化（第两个、倒数第散个）也照上表归一成阿拉伯数字理解。',
      ].join('\n'),
    })
  } catch {
    return () => {}
  }
}

/** 把一条提示写进状态并立刻推给页面（7 秒后页面自己收起来）。 */
let noticeTimer
function setNotice(text) {
  const next = String(text || '')
  clearTimeout(noticeTimer)
  if (next) {
    noticeTimer = setTimeout(() => {
      if (notice !== next) return
      notice = ''
      try { publishFloat('') } catch { }
    }, 8000)
  }
  if (next === notice) return
  notice = next
  try { publishFloat('') } catch { }
}

/** 清单还是空的就再问一次 llm 服务；成功后重发状态，菜单才有内容。 */
function maybeRefreshModels() {
  if (modelCatalog.length || !llmApi) return
  if (Date.now() < modelTryAt) return
  modelTryAt = Date.now() + 20000
  void refreshModels().then(() => { try { publishFloat('') } catch { } })
}

/** 上下文用量：token-meter 的投影（已用/窗口 + 系统提示词/工具/消息三段）。 */
function usageOf(session) {
  if (!projections || !session) return null
  try {
    const snap = projections.snapshot(session, ['contextPressure', 'contextBreakdown', 'tokenUsage'])
    const pressure = snap?.values?.contextPressure
    const breakdown = snap?.values?.contextBreakdown
    const totals = snap?.values?.tokenUsage?.totals
    const used = pressure?.projectedTokens ?? pressure?.pressureTokens
    // 累计用量与缓存命中率：dsh 底部那行「277M tok · 缓存命中 99.7%」
    let total = 0
    let cacheHit = null
    const outTokens = totals ? (Number(totals.outputTokens) || 0) : 0
    if (totals) {
      const read = Number(totals.cacheReadTokens) || 0
      total = (Number(totals.uncachedInputTokens) || 0) + (Number(totals.outputTokens) || 0)
        + read + (Number(totals.cacheWriteTokens) || 0)
      const input = (Number(totals.uncachedInputTokens) || 0) + read
      if (input > 0) cacheHit = Math.round((read / input) * 1000) / 10
    }
    if (!Number.isFinite(used) || !Number.isFinite(pressure?.contextWindow)) {
      return total ? { total, cacheHit } : null
    }
    return {
      used,
      window: pressure.contextWindow,
      system: Number(breakdown?.systemTokens) || 0,
      tools: Number(breakdown?.toolsTokens) || 0,
      messages: Number(breakdown?.messageTokens) || 0,
      total,
      cacheHit,
      outTokens,
    }
  } catch {
    return null
  }
}

/** 拉一次可用模型清单，供悬浮窗的模型菜单用。 */
async function refreshModels() {
  try {
    if (!llmApi) return
    const providers = await llmApi.listProviders()
    const out = []
    for (const provider of providers.slice(0, 4)) {
      const models = await llmApi.listModels(provider.id)
      for (const model of models.slice(0, 30)) {
        out.push({ provider: provider.id, id: model.id, name: model.name || model.id })
      }
    }
    modelCatalog = out
  } catch {
    modelCatalog = []
  }
}

/** 当前会话用的模型名（给悬浮窗的模型胶囊用）；拿不到就空串。 */
function modelOf(sessionId) {
  try {
    if (!agentsApi) return ''
    const agent = agentsApi.get(String(sessionId || ''))
    if (!agent) return ''
    const selection = agentsApi.selectionFor(agent)
    const current = selection && selection.current
    return current && current.model ? String(current.model) : ''
  } catch {
    return ''
  }
}

/** 整份状态原子写入文件，宿主轮询读取。写失败不影响 agent。 */
function writeState(payload) {
  try {
    const tmp = FLOAT_STATE + '.' + process.pid + '.tmp'
    writeFileSync(tmp, payload, { mode: 0o600 })
    renameSync(tmp, FLOAT_STATE)
  } catch { /* 悬浮窗不是关键路径 */ }
}

/** 不依赖某个会话的一次状态发布：dsh 启动时就该让悬浮窗出现。 */
function publishFloat(activeKey) {
  maybeRefreshModels()
  const tasks = []
  for (const [k, s] of state) {
    if (!s || !(s.messages || []).length) continue
    const usage = usageOf(s.session)
    tasks.push({
      id: fullIdOf(k) || k,
      title: titles.get(k) || '',
      messages: (s.messages || []).slice(-20),
      updated: s.updated || 0,
      done: !!s.done,
      usage,
      model: modelOf(k),
      permission: permissionOf(s.session),
      goal: goalOf(s.session),
      todos: todosOf(s.session),
      commands: commandsOf(s.session),
      since: s.turnStart || 0,
      stats: {
        turns: s.turns || 0,
        steps: Number(s.stepCount) || 0,
        total: usage?.total || 0,
        cacheHit: usage?.cacheHit ?? null,
        tps: s.genMs > 0 && s.outTokens > 0 ? Math.round(s.outTokens / (s.genMs / 1000)) : 0,
      },
    })
  }
  tasks.sort((left, right) => right.updated - left.updated)
  writeState(JSON.stringify({
    title: tasks.length ? tasks[0].title : '',
    tasks: tasks.slice(0, 6),
    active: fullIdOf(activeKey) || activeKey || (tasks.length ? tasks[0].id : ''),
    count: tasks.length,
    models: modelCatalog.slice(0, 16),
    threads: threadList.slice(0, 24),
    files: filesResult,
    notice,
  }))
}

/** 毫秒 → 「2 分 13 秒」/「9 秒」，和 dsh 的写法一致。 */
function duration(ms) {
  const total = Math.max(1, Math.round(ms / 1000))
  if (total < 60) return total + ' 秒'
  const m = Math.floor(total / 60)
  const sec = total % 60
  if (m < 60) return sec ? m + ' 分 ' + sec + ' 秒' : m + ' 分'
  const h = Math.floor(m / 60)
  return h + ' 小时 ' + (m % 60) + ' 分'
}

/** 把攒着的一段思考落成一条「思考」消息；同一轮里连续多段就并进同一条。 */
/**
 * 正文流：边写边更新一条「进行中的回答」（保留换行，悬浮窗迷你档就显示它），
 * 遇到工具调用或本轮结束时收尾成普通的一条回答。主会话与子代理各用各的槽位。
 */
function liveSlot(depth) {
  return depth ? 'live1' : 'live0'
}
function appendLive(b, depth, piece) {
  const slot = liveSlot(depth)
  const acc = (b[slot + 'Text'] || '') + piece
  b[slot + 'Text'] = acc.length > 6000 ? acc.slice(-6000) : acc
  let msg = b[slot]
  if (!msg || !b.messages.includes(msg)) {
    msg = { depth, kind: 'assistant', title: '', text: '', at: Date.now(), live: true }
    b.messages.push(msg)
    if (b.messages.length > 40) b.messages.splice(0, b.messages.length - 40)
    b[slot] = msg
  }
  const text = b[slot + 'Text'].trim()
  msg.title = text.replace(/\s+/g, ' ').slice(0, 48)
  msg.text = text.slice(-4000)
}
/** 收尾当前这段正文；返回是否真的有一段。 */
function closeLive(b, depth) {
  const slot = liveSlot(depth)
  const msg = b[slot]
  b[slot] = undefined
  b[slot + 'Text'] = ''
  if (!msg) return false
  delete msg.live
  if (!msg.text) {
    const i = b.messages.indexOf(msg)
    if (i >= 0) b.messages.splice(i, 1)
    return false
  }
  return true
}

/** 思考边写边更新一条「进行中的思考」，悬浮窗能实时看到；连续多段并进同一条。 */
function updateLiveThink(b, depth) {
  const acc = String(b.thinkAcc || '').trim()
  if (!acc) return
  let msg = b.thinkMsg
  if (!msg || !b.messages.includes(msg)) {
    const last = b.messages[b.messages.length - 1]
    if (last && last.kind === 'think') {
      msg = last
      b.thinkBase = String(last.text || '') + '\n'
    } else {
      msg = { depth, kind: 'think', title: '思考', text: '', at: Date.now() }
      b.messages.push(msg)
      if (b.messages.length > 40) b.messages.splice(0, b.messages.length - 40)
      b.thinkBase = ''
    }
    b.thinkMsg = msg
  }
  msg.live = true
  msg.text = (String(b.thinkBase || '') + acc).slice(-4000)
}

function pushThink(b, depth) {
  if (b.thinkMsg) {
    // 已经在实时更新的那条：收尾即可
    updateLiveThink(b, depth)
    delete b.thinkMsg.live
    b.thinkMsg.at = Date.now()
    b.thinkMsg = undefined
    b.thinkBase = ''
    b.thinkAcc = ''
    return
  }
  const acc = String(b.thinkAcc || '').trim()
  b.thinkAcc = ''
  if (!acc) return
  const last = b.messages[b.messages.length - 1]
  if (last && last.kind === 'think') {
    last.text = (last.text + '\n' + acc).slice(0, 4000)
    last.at = Date.now()
    return
  }
  b.messages.push({ depth, kind: 'think', title: '思考', text: acc.slice(0, 4000), at: Date.now() })
  if (b.messages.length > 24) b.messages.splice(0, b.messages.length - 24)
}

function pushStep(b, text) {
  const line = String(text || '').trim()
  if (!line) return
  b.steps.push(line.slice(0, 120))
  if (b.steps.length > 8) b.steps.splice(0, b.steps.length - 8)
}

function bucket(key) {
  let b = state.get(key)
  if (!b) {
    b = { line: '', think: '', timer: undefined, pending: false,
          status: '', detail: '', steps: [], ftimer: undefined, messages: [], done: false }
    state.set(key, b)
    // 会话数量不该无限涨（异常情况下也就几十个）
    if (state.size > 16) {
      const oldest = state.keys().next().value
      if (oldest !== key) {
        clearTimeout(state.get(oldest)?.timer)
        state.delete(oldest)
      }
    }
  }
  return b
}

/** 合并窗口内只保留最后一次内容，窗口结束时发一次。
 *  getter 而不是直接传字符串：窗口这 120ms 里还会继续追加，要发的是**那一刻**的最新值。 */
function schedule(key, kind, getter) {
  const b = bucket(key)
  if (b.timer) {
    b.pending = true
    return
  }
  b.timer = setTimeout(() => {
    b.timer = undefined
    b.pending = false
    void send(key, kind, getter ? getter() : b.line)
  }, FLUSH_MS)
  // 让 dsh 能正常退出：这个定时器不该拖住事件循环
  if (typeof b.timer?.unref === 'function') b.timer.unref()
}

/** 子功能各自兜底：任何一块出错只记诊断，绝不让 apply 抛出、把整个插件带崩。 */
function safely(what, run) {
  try {
    run()
  } catch (error) {
    pluginDiag(what, 'fail ' + String(error?.stack || error?.message || error).slice(0, 300))
  }
}

/** @param {import('@deepseek-ai/cordis').Context} ctx */
export function apply(ctx) {
  // 顺带把插件真实加载状态报给 App（见 reportPluginStates 的注释）
  reportPluginStates(ctx)
  // 悬浮窗：启动即出现（哪怕还没有消息），之后按状态文件更新。
  const started = setTimeout(() => {
    try { publishFloat('') } catch { }
  }, 300)
  if (typeof started?.unref === 'function') started.unref()
  safely('inbox', () => startInbox(ctx))
  safely('voice', () => startVoice(ctx))
  ctx.inject(['sessionProjections'], scoped => {
    projections = scoped.sessionProjections
    return () => { projections = undefined }
  })
  ctx.inject(['agents'], scoped => {
    agentsApi = scoped.agents
    return () => { agentsApi = undefined }
  })
  ctx.inject(['llm'], scoped => {
    llmApi = scoped.llm
    void refreshModels()
    return () => { llmApi = undefined }
  })
  // 会话管理工具/技能：走 inject（直接访问未声明的 ctx.tools 在 cordis 里可能直接抛错，
  // 那会把整个插件带崩——悬浮窗标签、语音转写全没了）
  safely('session-tool', () => ctx.inject(['tools'], scoped => registerSessionTool(scoped)))
  safely('session-skill', () => ctx.inject(['skills'], scoped => registerSessionSkill(scoped)))
  ctx.inject(['sessionQuery'], scoped => {
    queryApi = scoped.sessionQuery
    void refreshThreads()
    return () => { queryApi = undefined }
  })
  ctx.inject(['fileReferences'], scoped => {
    fileRefApi = scoped.fileReferences
    return () => { fileRefApi = undefined }
  })
  ctx.inject(['commands'], scoped => {
    commandsApi = scoped.commands
    return () => { commandsApi = undefined }
  })
  ctx.inject(['goals'], scoped => {
    goalsApi = scoped.goals
    return () => { goalsApi = undefined }
  })
  ctx.inject(['permissionPresets'], scoped => {
    permissionApi = scoped.permissionPresets
    return () => { permissionApi = undefined }
  })
  ctx.inject(['agentDefaultModel'], scoped => {
    defaultModelApi = scoped.agentDefaultModel
    return () => { defaultModelApi = undefined }
  })
  const onEvent = (session, event) => {
    try {
      if (!bridgeToken()) return
      const nest = nestOf(sessionKey(session), session)
      const key = nest.key
      const depth = nest.depth
      const type = event?.type
      const data = event?.data

      if (type === 'session/title') {
        const title = String(data?.title || '').replace(/\s+/g, ' ').trim()
        if (title) titles.set(key, title.slice(0, 24))
        return
      }
      if (!titles.get(key)) {
        const known = titleOf(key, session)
        if (known) titles.set(key, known)
      }

      if (type === 'assistant/chunk') {
        const chunk = data?.chunk
        const isText = chunk?.type === 'text-delta'
        const isThink = chunk?.type === 'reasoning-delta'
        if (!isText && !isThink) return
        if (isThink && Date.now() < skipReasoningUntil) return   // App 侧刚说过不看这个
        const piece = chunk.text
        if (!piece) return
        const b = bucket(key)
        if (!depth) b.session = session
        // 正文与思考各留一条缓冲：思考结束转正文时，两段不该被拼成一句
        if (isThink) {
          b.think = tail((b.think || '') + piece)
          b.thinkAcc = ((b.thinkAcc || '') + piece).slice(-4000)
          updateLiveThink(b, depth)
          b.updated = Date.now()
          schedule(key, 'reasoning', () => b.think)
          scheduleFloat(key, b)
        } else {
          if (b.thinkAcc) {
            pushThink(b, depth)
          }
          b.line = tail(b.line + piece)
          appendLive(b, depth, piece)
          // 不把原始 token 送到悬浮窗：只表达「正在生成」。
          b.status = '正在生成回复…'
          b.updated = Date.now()
          b.detail = ''
          scheduleFloat(key, b)
        }
        return
      }

      if (type === 'user/message') {
        // dsh 的 GUI 也这么分：source.kind === 'user' 才是人说的话，
        // 其余（运行时上下文、checkpoint、goal round 等）在对话里以「上下文行」出现，
        // 悬浮窗空间小，直接不列出来。
        const sourceKind = String(data?.source?.kind || data?.message?.source?.kind || '')
        if (sourceKind && sourceKind !== 'user') return
        const b = bucket(key)
        if (!depth) b.session = session
        const parts = (data?.message && data.message.content) || data?.content || []
        const text = Array.isArray(parts)
          ? parts.filter(p => p && p.type === 'text').map(p => p.text).join(' ').trim()
          : ''
        if (!text) return
        b.updated = Date.now()
        b.messages.push({ depth,
          kind: 'user',
          title: text.slice(0, 120),
          text: text.slice(0, 4000),
          at: Date.now(),
        })
        if (b.messages.length > 24) b.messages.splice(0, b.messages.length - 24)
        scheduleFloat(key, b)
        return
      }

      if (type === 'tool/call') {
        const b = bucket(key)
        if (!depth) b.session = session
        // 「正在执行命令」看不出到底要跑什么 —— 把命令原文（或路径/模式）带上，
        // 这也是悬浮条上就地批准危险命令时唯一的判断依据。
        const label = toolLabel(data?.name)
        const detail = toolDetail(data?.arguments)
        closeLive(b, depth)
        b.line = detail ? label + ': ' + detail : label
        b.think = ''
        // 工具状态要立刻可见（它替代整行，且不像正文那样高频）
        if (b.timer) {
          clearTimeout(b.timer)
          b.timer = undefined
        }
        if (b.thinkAcc) {
          pushThink(b, depth)
        }
        b.stepCount = (b.stepCount || 0) + 1
        b.updated = Date.now()
        b.detail = detail || ''
        pushStep(b, b.line)
        // 同一个工具连着跑（比如一屏四条「正在执行命令」）就并成一条计数，
        // 不然悬浮窗里全是重复行，真正的内容被挤没了。
        const last = b.messages[b.messages.length - 1]
        if (last && last.kind === 'tool' && last.title === label) {
          last.count = (last.count || 1) + 1
          last.text = detail || last.text
          last.at = Date.now()
        } else {
          b.messages.push({ depth, kind: 'tool', title: label, text: detail || '', at: Date.now() })
        }
        if (b.messages.length > 24) b.messages.splice(0, b.messages.length - 24)
        scheduleFloat(key, b)
        return
      }

      if (type === 'tool/result') {
        const b = bucket(key)
        let row = null
        for (let i = b.messages.length - 1; i >= 0; i--) {
          const m = b.messages[i]
          if (m.kind === 'tool' && m.failed === undefined) { row = m; break }
        }
        if (!row) return
        let code = null
        try {
          const text = JSON.stringify(data?.message?.content ?? data?.content ?? '')
          const hit = /(?:exit code|退出码)[:：\s]*(\d+)/i.exec(text)
          if (hit) code = Number(hit[1])
        } catch { }
        row.failed = !!data?.error || (code !== null && code !== 0)
        if (code !== null) row.code = code
        b.updated = Date.now()
        scheduleFloat(key, b)
        return
      }

      if (type === 'turn/start') {
        const b = bucket(key)
        if (!depth) b.session = session
        b.line = ''
        b.think = ''
        b.thinkAcc = ''
        if (b.thinkMsg) { delete b.thinkMsg.live; b.thinkMsg = undefined }
        b.thinkBase = ''
        closeLive(b, depth)
        b.turnStart = Date.now()
        b.turns = (b.turns || 0) + 1
        b.done = false
        b.updated = Date.now()
        b.status = '正在处理…'
        b.detail = ''
        scheduleFloat(key, b)
        return
      }

      if (type === 'turn/end') {
        const b = bucket(key)
        if (!depth) b.session = session
        if (b.timer) {
          clearTimeout(b.timer)
          b.timer = undefined
        }
        if (b.thinkAcc) pushThink(b, depth)
        const hadLive = closeLive(b, depth)
        const finished = hadLive ? '' : (b.line || '').replace(/\s+/g, ' ').trim()
        if (finished) {
          b.messages.push({ depth,
            kind: 'assistant',
            title: finished.slice(0, 48),
            text: finished.slice(0, 4000),
            at: Date.now(),
          })
          if (b.messages.length > 24) b.messages.splice(0, b.messages.length - 24)
        }
        b.line = ''
        b.updated = Date.now()
        b.detail = ''
        b.done = true
        // dsh 会在这一轮结束时写「已完成，用时 …」，悬浮窗也留一行，方便回看花了多久
        const spent = b.turnStart ? Date.now() - b.turnStart : 0
        b.turnStart = 0
        // tok/s：本轮输出 token 的增量 ÷ 本轮耗时（跨轮累加后取平均，和 dsh 的读数口径接近）
        try {
          const u = usageOf(session)
          if (u && Number.isFinite(u.outTokens)) {
            const delta = Math.max(0, u.outTokens - (b.lastOut || 0))
            b.lastOut = u.outTokens
            if (delta > 0 && spent > 0) {
              b.outTokens = (b.outTokens || 0) + delta
              b.genMs = (b.genMs || 0) + spent
            }
          }
        } catch { }
        if (spent >= 1000) {
          b.messages.push({ depth, kind: 'note', title: '已完成', text: '已完成，用时 ' + duration(spent), at: Date.now() })
          if (b.messages.length > 24) b.messages.splice(0, b.messages.length - 24)
        }
        void maybeRefreshThreads()
        scheduleFloat(key, b)
      }
    } catch {
      // 事件回调里抛异常会影响 agent 主链路，一律吞掉
    }
  }
  ctx.on('session/event', onEvent)
  // dsh 0.1.5 的增量属于进程内流，持久日志只在一次尝试结束时写完整 assistant/message。
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    try {
      if (!agent?.session || !frame) return
      const key = sessionKey(agent.session)
      if (frame.type === 'start') {
        const b = bucket(key)
        clearTimeout(b.timer)
        b.timer = undefined; b.line = ''; b.think = ''
      } else if (frame.type === 'chunk') {
        onEvent(agent.session, { type: 'assistant/chunk', data: { chunk: frame.chunk } })
      }
    } catch { /* 悬浮条不能中断模型流。 */ }
  })

  ctx.on('dispose', () => {
    try {
      for (const [key, b] of state) {
        if (b.timer) clearTimeout(b.timer)
        void send(key, 'clear', '')
      }
      state.clear()
      writeState(JSON.stringify({ clear: true, tasks: [] }))
    } catch {
      // 卸载路径上的清理，失败无所谓
    }
  })
}


let stt

/** 语音转写：用 dsh 自己的 speechToText 服务（SenseVoice 等），不是系统识别。
 *  两路：悬浮窗录的一段话（float-voice）和语音唤醒切出的短句（float-wake）。 */
function startVoice(ctx) {
  ctx.inject(['speechToText'], scoped => {
    stt = scoped.speechToText
    return () => { stt = undefined }
  })
  const timers = [
    ...watchVoice(ctx, VOICE_IN, VOICE_OUT, 400),
    ...watchVoice(ctx, WAKE_IN, WAKE_OUT, 250),
  ]
  ctx.on('dispose', () => {
    for (const timer of timers) {
      try { clearInterval(timer) } catch { }
    }
  })
}

function watchVoice(ctx, input, output, every) {
  let lastSeq = 0
  let busy = false
  try {
    const seed = JSON.parse(readFileSync(input, 'utf8'))
    if (seed && Number.isFinite(Number(seed.seq))) lastSeq = Number(seed.seq)
  } catch { /* 首次没有录音 */ }
  const write = result => {
    try {
      const tmp = output + '.' + process.pid + '.tmp'
      writeFileSync(tmp, JSON.stringify(result), { mode: 0o600 })
      renameSync(tmp, output)
    } catch { /* 结果写不进去就保持安静 */ }
  }
  // 看门狗：busy 卡死（推理进程挂掉等）超过 35 秒就强制复位，别让队列永远堵着
  const watchdog = setInterval(() => {
    if (busy && Date.now() - busyAt > 35000) {
      busy = false
      write({ seq: busySeq, error: '识别超时（已重置），请重试' })
    }
  }, 5000)
  if (typeof watchdog?.unref === 'function') watchdog.unref()
  let busyAt = 0
  let busySeq = 0
  const timer = setInterval(async () => {
    if (busy) return
    try {
      const message = JSON.parse(readFileSync(input, 'utf8'))
      const seq = Number(message?.seq)
      if (!Number.isFinite(seq) || seq <= lastSeq || typeof message.audio !== 'string') return
      if (!stt) {
        // 服务确实没装/没启用才报错；只是还没注入完就再等等
        if (ctx.get && ctx.get('speechToText')) return
        lastSeq = seq
        write({ seq, error: '语音转写未启用（需要 speech-to-text 插件）' })
        return
      }
      lastSeq = seq
      busy = true
      busyAt = Date.now()
      busySeq = seq
      const audio = Buffer.from(message.audio, 'base64')
      try {
        const spec = stt.resolve({ audio, language: 'zh' })
        const controller = new AbortController()
        // 硬超时：单段最长 25 秒；到点主动取消，绝不让一次卡死堵住整个通道
        const limit = setTimeout(() => controller.abort(), 25000)
        let result
        try {
          result = await stt.transcribe(spec, controller.signal)
        } finally {
          clearTimeout(limit)
        }
        const text = typeof result === 'string'
          ? result
          : (result?.text ?? result?.transcript ?? '')
        write({ seq, text: String(text || '') })
      } catch (error) {
        write({ seq, error: '语音转写失败：' + String(error?.message || error).slice(0, 80) })
      } finally {
        busy = false
        busyAt = 0
      }
    } catch { /* 轮询本身出错不影响 agent */ }
  }, every)
  if (typeof timer?.unref === 'function') timer.unref()
  return [timer, watchdog]
}

/** 悬浮窗里的输入框 → 会话：宿主把文本写进 FLOAT_INBOX，这里轮询并投递。 */
function startInbox(ctx) {
  let agents
  let attachments
  let lastSeq = 0
  try {
    const seed = JSON.parse(readFileSync(FLOAT_INBOX, 'utf8'))
    if (seed && Number.isFinite(Number(seed.seq))) lastSeq = Number(seed.seq)
  } catch { /* 首次没有收件箱 */ }
  ctx.inject(['agents'], scoped => {
    agents = scoped.agents
    return () => { agents = undefined }
  })
  ctx.inject(['attachments'], scoped => {
    attachments = scoped.attachments
    return () => { attachments = undefined }
  })
  const drain = async () => {
    try {
      if (!agents) return
      const raw = readFileSync(FLOAT_INBOX, 'utf8')
      const msg = JSON.parse(raw)
      const seq = Number(msg?.seq)
      if (!Number.isFinite(seq) || seq <= lastSeq) return
      const text = typeof msg?.text === 'string' ? msg.text : ''
      const image = typeof msg?.image === 'string' ? msg.image : ''
      const file = typeof msg?.file === 'string' ? msg.file : ''
      const cancel = msg?.cancel === true
      const hasModel = typeof msg?.model === 'string' && !!msg.model
      const want = typeof msg?.want === 'string' ? msg.want : ''
      lastSeq = seq
      // 宿主只是要一份数据（目前是模型清单）：不算用户消息
      if (want === 'models') {
        await refreshModels()
        publishFloat('')
        return
      }
      if (want === 'threads') {
        await refreshThreads()
        return
      }
      if (want === 'files') {
        const realQuery = String(msg.value || '')
        let target
        try {
          target = await ensureAgent(msg.session)
        } catch {
          filesResult = { query: realQuery, items: [] }
          publishFloat('')
          return
        }
        let items = []
        try {
          if (target && fileRefApi) {
            const found = await fileRefApi.list(target, realQuery, AbortSignal.timeout(5000))
            items = (found || []).slice(0, 30).map(c => ({ p: String(c.path), k: String(c.kind) }))
          }
        } catch {
          items = []
        }
        filesResult = { query: realQuery, items }
        publishFloat('')
        return
      }
      if (want === 'goal') {
        const action = String(msg.value || '').trim()
        if (!['pause', 'resume', 'clear'].includes(action)) return
        let target
        try {
          target = await ensureAgent(msg.session)
        } catch (error) {
          setNotice('目标操作失败：' + String(error?.message || error).slice(0, 60))
          return
        }
        if (!target || !goalsApi) {
          setNotice('目标操作失败：当前环境不支持')
          return
        }
        try {
          const view = goalsApi.get(target)
          if (!view) {
            setNotice('当前没有进行中的目标')
          } else {
            const ref = { id: view.id, revision: view.revision }
            if (action === 'pause') goalsApi.pause(target, ref)
            else if (action === 'resume') goalsApi.resume(target, ref)
            else goalsApi.clear(target, ref)
            notice = ''
          }
        } catch (error) {
          setNotice('目标操作失败：' + String(error?.message || error).slice(0, 70))
        }
        publishFloat('')
        return
      }
      if (want === 'permission') {
        const value = String(msg.value || '').trim()
        if (!value) return
        let target
        try {
          target = await ensureAgent(msg.session)
        } catch (error) {
          setNotice('切换权限失败：' + String(error?.message || error).slice(0, 60))
          return
        }
        const commands = typeof ctx.get === 'function' ? ctx.get('commands') : undefined
        if (!target || !commands || typeof commands.execute !== 'function') {
          setNotice('切换权限失败：当前环境不支持')
          return
        }
        try {
          const done = await commands.execute(target, '/permission ' + value, [], AbortSignal.timeout(8000))
          const kind = done?.result?.kind
          if (kind === 'error') {
            setNotice('切换权限失败：' + String(done?.result?.text || '').slice(0, 70))
          } else {
            notice = ''
          }
        } catch (error) {
          setNotice('切换权限失败：' + String(error?.message || error).slice(0, 70))
        }
        publishFloat('')
        return
      }
      if (want === 'delete') {
        // 悬浮窗会话面板里的删除：页面已经点过两次确认，这里只做执行与回执
        const result = await sessionAdmin({ action: 'delete', session: String(msg.session || ''), confirm: true })
        setNotice(result.text)
        publishFloat('')
        return
      }
      if (want === 'thread') {
        const id = String(msg.session || '').trim()
        const ok = id ? await loadHistory(id) : false
        if (!ok) setNotice('读不到这个会话的历史记录')
        return
      }
      if (!text.trim() && !image && !file && !cancel && !hasModel) return
      // 页面手上的 id 可能是旧的、短的、甚至空的；没有任何活会话时直接新建一个，
      // 用户的要求是「dsh 起着就能聊」，不该让他先去别处开个对话。
      let agent
      try {
        agent = await ensureAgent(msg.session)
      } catch (error) {
        setNotice('新建会话失败：' + String(error?.message || error).slice(0, 70))
        return
      }
      if (!agent) {
        setNotice('还没有可用的会话，稍后重试或进 RCHA 里发起一次对话')
        return
      }
      if (hasModel) {
        try {
          const selection = { provider: String(msg.provider || ''), model: String(msg.model) }
          if (typeof agents.selectForNextRequest === 'function') {
            agents.selectForNextRequest(agent, selection)
          }
          notice = ''
        } catch {
          setNotice('切换模型失败：' + String(msg.model))
        }
        publishFloat('')
        return
      }
      if (cancel) {
        try { agent.cancel({ kind: 'user' }, { keepInbox: true }) } catch { }
        return
      }
      // / 开头的输入如果命中已注册命令，就走 dsh 的命令执行路径（和 Web 一样）
      if (text.trim().startsWith('/')) {
        const name = text.trim().slice(1).split(/\s+/)[0]
        const known = agents && commandsApi ? (commandsApi.list(agent) || []) : []
        if (name && known.some(c => c.name === name)) {
          try {
            const done = await commandsApi.execute(agent, text.trim(), [], AbortSignal.timeout(15000))
            const kind = done?.result?.kind
            if (kind === 'error') {
              setNotice('指令失败：' + String(done?.result?.text || '').slice(0, 70))
            } else {
              notice = ''
            }
          } catch (error) {
            setNotice('指令失败：' + String(error?.message || error).slice(0, 70))
          }
          publishFloat('')
          return
        }
      }
      const { createUserMessage } = await import('@deepseek-ai/dsh-llm')
      const parts = []
      if (text.trim()) parts.push({ type: 'text', text: text.slice(0, 8000) })
      if (image && attachments) {
        try {
          const bytes = readFileSync(image)
          const mediaType = /\.png$/i.test(image)
            ? 'image/png'
            : /\.webp$/i.test(image)
              ? 'image/webp'
              : /\.gif$/i.test(image)
                ? 'image/gif'
                : 'image/jpeg'
          parts.push({
            type: 'image',
            data: bytes.toString('base64'),
            mediaType,
            name: image.split('/').pop(),
          })
        } catch { /* 图片读不到就只发文字 */ }
      }
      if (file && attachments) {
        try {
          const bytes = readFileSync(file)
          const ref = await attachments.admitEncodedFile({
            data: bytes.toString('base64'),
            name: file.split('/').pop(),
          })
          parts.push({ type: 'file', attachment: ref })
        } catch { /* 文件读不到就只发文字 */ }
      }
      if (!parts.length) return
      const content = attachments ? await attachments.admitPromptContent(parts) : parts
      try {
        agent.followup(createUserMessage({ content, source: { kind: 'user' } }))
        // 送达就把上一次的失败提示撤掉（页面 7 秒后本来也会自己收）
        if (notice) setNotice('')
        // 新建/恢复出来的会话要出现在「所有对话」里
        void maybeRefreshThreads()
      } catch (error) {
        setNotice('发送失败：' + String(error?.message || error).slice(0, 60))
      }
    } catch { /* 投递失败不能影响 agent 主链路 */ }
  }
  const timer = setInterval(() => { void drain() }, 400)
  if (typeof timer?.unref === 'function') timer.unref()
  ctx.on('dispose', () => {
    try { clearInterval(timer) } catch { }
  })
}

/**
 * 把「插件真实加载状态」报给 App 的 3090 桥。
 *
 * 为什么需要它：**注册进 profile 不等于加载成功**。cordis 的 PENDING 是最隐蔽的一种状态 ——
 * 插件 inject 的服务没有提供者时它就停在那儿，而且**不报错**（那是合法状态，服务可能稍后
 * 才挂载），于是插件静静地什么都不做。官方教程把「插件什么都没干也没报错 → 去看 fiber
 * 状态」当作标准诊断法，而 App 侧只能读 profile 的 package.json，永远看不到这一层。
 * 「插件装了没反应」缺的就是这份证据。
 *
 * 全程宽容：registry 的形状随 cordis 版本可能变，取不到就少报一点，绝不影响悬浮条本身。
 */
export function collectPluginStates(ctx) {
  const loaded = new Set(), failed = new Set(), pending = new Set()
  try {
    const reg = ctx && ctx.registry
    const iter = reg && typeof reg.entries === 'function' ? reg.entries() : reg
    for (const entry of iter || []) {
      const runtime = Array.isArray(entry) ? entry[1] : entry
      if (!runtime) continue
      const nm = runtime.name || (runtime.callback && runtime.callback.name)
      if (!nm) continue
      let fibers = []
      try {
        fibers = runtime.fibers ? Array.from(runtime.fibers) : []
      } catch (e) {
        fibers = []
      }
      for (const f of fibers) {
        const raw = f && f.state !== undefined ? f.state : ''
        const st = String(raw).toLowerCase()
        // 锁定的 Cordis：PENDING=0 / LOADING=1 / ACTIVE=2 / FAILED=3。
        // 等待、卸载和未知状态都不是异常；同插件一个实例失败也不能被另一个成功掩盖。
        if (st === 'active' || st === '2') {
          loaded.add(nm)
        } else if (st === 'failed' || st === '3') failed.add(nm + ':加载失败')
        else if (st === 'pending' || st === '0') pending.add(nm + ':等待依赖')
        else if (st === 'loading' || st === '1') pending.add(nm + ':正在加载')
      }
    }
  } catch (e) {
    // registry 结构对不上就别报了：少一份诊断信息可以接受，插件本身出问题不行
  }
  return { loaded: [...loaded].sort(), failed: [...failed].sort(), pending: [...pending].sort() }
}

export function reportPluginStates(ctx, io = {}) {
  const getToken = io.token || bridgeToken, request = io.fetch || fetch
  const later = io.setTimeout || setTimeout, cancel = io.clearTimeout || clearTimeout
  let timer, disposed = false, sending = false, dirty = false, previous
  function schedule(delay = 300) {
    if (disposed) return
    dirty = true
    if (sending) return
    cancel(timer)
    timer = later(flush, delay); timer?.unref?.()
  }
  async function flush() {
    timer = undefined
    if (disposed || sending) return
    sending = true; dirty = false
    try {
      const T = getToken()
      if (!T) return
      const st = collectPluginStates(ctx)
      const snapshot = JSON.stringify(st)
      if (snapshot === previous) return
      const url = 'http://127.0.0.1:3090/app/plugins'
        + '?loaded=' + encodeURIComponent(st.loaded.join(','))
        + '&failed=' + encodeURIComponent(st.failed.join(','))
        + '&pending=' + encodeURIComponent(st.pending.join(','))
      const response = await request(url, { headers: { 'X-Token': T }, signal: AbortSignal.timeout(1500) })
      if (response.ok && unwrap(await response.text()) === 'OK') previous = snapshot
    } catch (e) {
      // 上报失败不影响任何既有功能
    } finally {
      sending = false
      if (!disposed) schedule(dirty ? 300 : 30_000)
    }
  }
  // 服务变化和 HMR 后重新采样；定期重试允许桥晚于插件启动。
  for (const event of ['internal/status', 'internal/plugin']) ctx.on(event, () => schedule())
  ctx.on('dispose', () => { disposed = true; cancel(timer) })
  schedule(1000)
}
