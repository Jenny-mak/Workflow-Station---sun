export default {
  aiStudioGuide: {
    pageTitle: 'Build with AI（AI Studio）',
    crumb: '開發工作站 · 功能單元 · AI Studio',
    intro:
      '功能單元上的 AI Studio 按鈕會開啟 Build with AI 彈窗。彈窗提供三種進入方式：和 Copilot 一起逐階段設計、繼續上次的草稿，或寫下需求讓 AI 一次產生整個功能單元。唯讀成員看不到這個按鈕。',
    flowTitle: '操作順序（One-click generate）',
    flow1: '開啟功能單元，點 AI Studio',
    flow2: '選取 One-click generate',
    flow3: '寫下這個功能單元要做什麼',
    flow4: '點 Generate，在 Replace the current design? 裡再點 Generate',
    flow5: 'AI Studio 停在 Process Design 等待結果。Stop 取消這一輪',
    flow6: 'Proposed change 卡片顯示 Applied 後，逐階段檢查並點 Confirm phase',
    whatTitle: 'Build with AI 是什麼',
    whatBody:
      'AI Studio 是一個全螢幕工作台，十一個階段與設計器頁籤同序，從 Process Design 到 Validation。中間是目前階段的設計器，右邊是 AI Copilot。Build with AI 是它前面的彈窗，決定你怎樣進入工作台，以及要不要讓 AI 先寫出第一版設計。',
    entryFigure: 'Build with AI。三張模式卡、AI Studio 會經過的階段清單，以及 Open AI Studio。',
    modesTitle: '三種模式',
    modesBody:
      '選取一張卡，再點底部右側的按鈕。底部說明寫著：Your existing design will not be overwritten without confirmation。',
    catNew:
      '從 Process Design 開始，和 Copilot 一起逐階段設計。這個功能單元已有 AI Studio 進度時，會先彈出 Start a new AI design? 確認再重設進度。設計本身不動。',
    catContinue:
      '回到 AI Studio，停在草稿上次所在的階段。在你或隊友確認過階段、或在這裡和 Copilot 對話過之前，它是停用的，顯示 No AI draft to resume for this Function Unit yet。',
    catOneClick: '寫下需求，讓 AI 一輪寫出流程、資料表、表單、動作和決策。階段清單換成需求輸入框。',
    catOpen: '前兩種模式的底部按鈕。開啟工作台。',
    catCancel: '關閉彈窗。什麼都不改。',
    oneClickTitle: 'One-click generate',
    oneClickBody:
      '描述、目前設計和功能單元的兩份文件（見 [[/fu-documents]]）會一起交給模型。需求超過輸入框上限時，先把它匯入為 Requirements 文件，輸入框裡寫短一些或留空。',
    oneClickScope:
      '一輪會寫出流程、資料表及其關係、表單、動作和決策。不會寫檢視、連線、郵件範本、郵件監控和自動化綁定。這些之後在各自階段補：[[/view-design]]、[[/email-send]]、[[/email-monitor]]。',
    oneClickFigure: '選取 One-click generate。需求輸入框取代階段清單，底部按鈕變成 Generate。',
    catInput:
      '用自然語言寫需求，最多 4000 字元。必填，除非功能單元已有 Requirements 文件。兩者都沒有時 Generate 保持停用。',
    catGenerate: '這種模式下的底部按鈕。開啟確認框。確認之後才提交作業。',
    catConfirm:
      '每次都會出現的確認框。Generate：結果一通過驗證就取代整套設計，AI Studio 的階段進度回到 Process Design。Cancel：不提交。',
    resultTitle: '等待結果',
    resultBody:
      '確認後 AI Studio 停在 Process Design。你的描述出現在 AI Copilot 裡，並提示正在產生提案。一輪通常 7 到 15 分鐘。可以保持頁面開啟、重新整理或離開。結果會保留，下次在這個功能單元上開啟 AI Studio 時顯示。',
    resultDocs:
      '設計寫入後，描述會儲存為 Requirements 的新版本，來源是 AI Studio · one-click generation。Requirements 原來有內容時，描述追加在末尾的 Additional requirements 一節，原有內容不動。',
    resultFigure:
      '在 Purchase Request 上 One-click generate 的結果。右：按階段分組的 Proposed change 卡片，標記 Applied。中：Process Design 已重新載入產生的流程。',
    catStop: '一輪進行中時取代 Propose change。取消這一輪。設計不變。',
    catCard: '結果卡片。每個階段一組，列出產生的項目：new N 表示新增，replaces N existing 表示取代了原有的。',
    catPreCheck: '寫入前檢查出的問題。橘色警告不阻止寫入。紅色錯誤會阻止。',
    catApplied: '卡片上的設計已經寫入功能單元。中間的設計器已重新載入。',
    catApply: '設計已產生但沒有寫入時顯示。點它自行寫入。確認框會說明取代多少個現有項目。',
    catFix: '卡片列出錯誤時顯示。把錯誤交回去，整套重新產生一次。修正請求不會存進 Requirements。',
    catConfirmPhase: '檢查完一個階段後點它，把該階段標為完成並進入下一個。',
    failTitle: '失敗時',
    failNoInput: 'Generate 停用：輸入框為空，功能單元也沒有 Requirements 文件。先寫描述，或先匯入文件。',
    failCredentials: 'AI credentials are missing, please sign in again：登出重新登入，再開啟 Build with AI。彈窗不會關閉，也沒有提交任何東西。',
    failErrors:
      '卡片列出錯誤，並顯示 Apply would be rejected — revise the request and propose again：設計沒有寫入。點 Let AI fix it，或回到 Build with AI 把需求寫得更具體。',
    failAutoApply: '卡片帶一條警告，說明設計已產生但無法自動寫入，常見原因是當時有其他人正在寫入 AI 改動。點卡片上的 Apply。',
    failGeneration: 'AI Copilot 裡出現紅色訊息而不是卡片：這一輪失敗，設計沒有變。開啟 Build with AI 重新產生。',
    failUndo: 'One-click generate 沒有 Undo。要回退，在 Version Management 裡回滾到之前的版本。',
    failNotGenerated: '一輪結束後 View Design、Connections、Email Templates、Email Monitors 和 Automation 仍是空的。這是預期行為。',
  },
}
