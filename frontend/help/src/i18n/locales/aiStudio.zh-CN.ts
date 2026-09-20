export default {
  aiStudioGuide: {
    pageTitle: 'Build with AI（AI Studio）',
    crumb: '开发工作站 · 功能单元 · AI Studio',
    intro:
      '功能单元上的 AI Studio 按钮打开 Build with AI 弹窗。弹窗给出三种进入方式：和 Copilot 一起逐阶段设计、继续上次的草稿，或写下需求让 AI 一次生成整个功能单元。只读成员看不到这个按钮。',
    flowTitle: '操作顺序（One-click generate）',
    flow1: '打开功能单元，点 AI Studio',
    flow2: '选中 One-click generate',
    flow3: '写下这个功能单元要做什么',
    flow4: '点 Generate，在 Replace the current design? 里再点 Generate',
    flow5: 'AI Studio 停在 Process Design 等待结果。Stop 取消这一轮',
    flow6: 'Proposed change 卡片显示 Applied 后，逐阶段检查并点 Confirm phase',
    whatTitle: 'Build with AI 是什么',
    whatBody:
      'AI Studio 是一个全屏工作台，十一个阶段与设计器页签同序，从 Process Design 到 Validation。中间是当前阶段的设计器，右边是 AI Copilot。Build with AI 是它前面的弹窗，决定你怎样进入工作台，以及要不要让 AI 先写出第一版设计。',
    entryFigure: 'Build with AI。三张模式卡、AI Studio 会经过的阶段列表，以及 Open AI Studio。',
    modesTitle: '三种模式',
    modesBody:
      '选中一张卡，再点底部右侧的按钮。底部说明写着：Your existing design will not be overwritten without confirmation。',
    catNew:
      '从 Process Design 开始，和 Copilot 一起逐阶段设计。这个功能单元已有 AI Studio 进度时，会先弹出 Start a new AI design? 确认再重置进度。设计本身不动。',
    catContinue:
      '回到 AI Studio，停在草稿上次所在的阶段。在你或队友确认过阶段、或在这里和 Copilot 对话过之前，它是禁用的，显示 No AI draft to resume for this Function Unit yet。',
    catOneClick: '写下需求，让 AI 一轮写出流程、表、表单、动作和决策。阶段列表换成需求输入框。',
    catOpen: '前两种模式的底部按钮。打开工作台。',
    catCancel: '关闭弹窗。什么都不改。',
    oneClickTitle: 'One-click generate',
    oneClickBody:
      '描述、当前设计和功能单元的两份文档（见 [[/fu-documents]]）会一起交给模型。需求超过输入框上限时，先把它导入为 Requirements 文档，输入框里写短一些或留空。',
    oneClickScope:
      '一轮会写出流程、表及其关系、表单、动作和决策。不会写视图、连接、邮件模板、邮件监控和自动化绑定。这些之后在各自阶段补：[[/view-design]]、[[/email-send]]、[[/email-monitor]]。',
    oneClickFigure: '选中 One-click generate。需求输入框取代阶段列表，底部按钮变成 Generate。',
    catInput:
      '用自然语言写需求，最多 4000 字符。必填，除非功能单元已有 Requirements 文档。两者都没有时 Generate 保持禁用。',
    catGenerate: '这种模式下的底部按钮。打开确认框。确认之后才提交作业。',
    catConfirm:
      '每次都会出现的确认框。Generate：结果一通过校验就替换整套设计，AI Studio 的阶段进度回到 Process Design。Cancel：不提交。',
    resultTitle: '等待结果',
    resultBody:
      '确认后 AI Studio 停在 Process Design。你的描述出现在 AI Copilot 里，并提示正在生成提案。一轮通常 7 到 15 分钟。可以保持页面打开、刷新或离开。结果会保留，下次在这个功能单元上打开 AI Studio 时显示。',
    resultDocs:
      '设计写入后，描述会保存为 Requirements 的新版本，来源是 AI Studio · one-click generation。Requirements 原来有内容时，描述追加在末尾的 Additional requirements 一节，原有内容不动。',
    resultFigure:
      '在 Purchase Request 上 One-click generate 的结果。右：按阶段分组的 Proposed change 卡片，标记 Applied。中：Process Design 已重新载入生成的流程。',
    catStop: '一轮进行中时取代 Propose change。取消这一轮。设计不变。',
    catCard: '结果卡片。每个阶段一组，列出生成的条目：new N 表示新增，replaces N existing 表示替换了原有的。',
    catPreCheck: '写入前检查出的问题。橙色警告不阻止写入。红色错误会阻止。',
    catApplied: '卡片上的设计已经写入功能单元。中间的设计器已重新载入。',
    catApply: '设计已生成但没有写入时显示。点它自行写入。确认框会说明替换多少个现有条目。',
    catFix: '卡片列出错误时显示。把错误交回去，整套重新生成一次。修正请求不会存进 Requirements。',
    catConfirmPhase: '检查完一个阶段后点它，把该阶段标为完成并进入下一个。',
    failTitle: '失败时',
    failNoInput: 'Generate 禁用：输入框为空，功能单元也没有 Requirements 文档。先写描述，或先导入文档。',
    failCredentials: 'AI credentials are missing, please sign in again：退出重新登录，再打开 Build with AI。弹窗不会关闭，也没有提交任何东西。',
    failErrors:
      '卡片列出错误，并显示 Apply would be rejected — revise the request and propose again：设计没有写入。点 Let AI fix it，或回到 Build with AI 把需求写得更具体。',
    failAutoApply: '卡片带一条警告，说明设计已生成但无法自动写入，常见原因是当时有其他人正在写入 AI 改动。点卡片上的 Apply。',
    failGeneration: 'AI Copilot 里出现红色消息而不是卡片：这一轮失败，设计没有变。打开 Build with AI 重新生成。',
    failUndo: 'One-click generate 没有 Undo。要回退，在 Version Management 里回滚到之前的版本。',
    failNotGenerated: '一轮结束后 View Design、Connections、Email Templates、Email Monitors 和 Automation 仍是空的。这是预期行为。',
  },
}
