export default {
  aiStudioGuide: {
    pageTitle: 'Build with AI (AI Studio)',
    crumb: 'Developer Workstation · Function Units · AI Studio',
    intro:
      'The AI Studio button on a Function Unit opens the Build with AI dialog. It offers three ways in: build phase by phase with the copilot, resume a draft, or describe the requirements and let AI generate the whole Function Unit. Read-only members do not see the button.',
    flowTitle: 'Order of work (One-click generate)',
    flow1: 'Open the Function Unit and click AI Studio',
    flow2: 'Select One-click generate',
    flow3: 'Describe what the Function Unit should do',
    flow4: 'Click Generate, then Generate again in Replace the current design?',
    flow5: 'AI Studio opens on Process Design and waits for the result. Stop cancels the round',
    flow6: 'When the Proposed change card shows Applied, review each phase and click Confirm phase',
    whatTitle: 'What Build with AI is',
    whatBody:
      'AI Studio is a full-screen workspace with eleven phases in the same order as the designer tabs, from Process Design to Validation. The designer of the current phase sits in the middle and AI Copilot on the right. Build with AI is the dialog in front of it. It decides how you enter the workspace and whether AI writes the first design for you.',
    entryFigure:
      'Build with AI. Three mode cards, the list of phases AI Studio walks through, and Open AI Studio.',
    modesTitle: 'The three modes',
    modesBody:
      'Select one card, then click the button on the right of the footer. The footer note reads: Your existing design will not be overwritten without confirmation.',
    catNew:
      'Start at Process Design and work through the phases with the copilot. If this Function Unit already has AI Studio progress, Start a new AI design? asks before the progress is reset. The design itself stays.',
    catContinue:
      'Reopen AI Studio at the phase where the draft stopped. Disabled with No AI draft to resume for this Function Unit yet until you or a teammate has confirmed a phase or talked to the copilot here.',
    catOneClick:
      'Type the requirements and let AI write process, tables, forms, actions and decisions in one round. The phase list gives way to the requirements box.',
    catOpen: 'Footer button for the first two modes. Opens the workspace.',
    catCancel: 'Close the dialog. Nothing changes.',
    oneClickTitle: 'One-click generate',
    oneClickBody:
      'The description, the current design and the two documents of the Function Unit (see [[/fu-documents]]) go to the model together. For requirements longer than the box allows, import them as the Requirements document first and leave the box short or empty.',
    oneClickScope:
      'One round writes the process, the tables and their relations, the forms, the actions and the decisions. It does not write views, connections, email templates, email monitors or automation bindings. Add those in their phases afterwards: [[/view-design]], [[/email-send]], [[/email-monitor]].',
    oneClickFigure: 'One-click generate selected. The requirements box replaces the phase list and the footer button reads Generate.',
    catInput:
      'Requirements in plain language, up to 4000 characters. Required unless the Function Unit already has a Requirements document. With neither, Generate stays disabled.',
    catGenerate: 'Footer button in this mode. Opens the confirmation. The job is submitted only after you confirm.',
    catConfirm:
      'Confirmation shown every time. Generate: the result replaces the whole design as soon as it passes validation, and the AI Studio phase progress goes back to Process Design. Cancel: nothing is submitted.',
    resultTitle: 'Waiting for the result',
    resultBody:
      'After you confirm, AI Studio opens on Process Design. Your description appears in AI Copilot and a note says the proposal is being generated. A round usually takes 7 to 15 minutes. You can keep the page open, reload it or leave. The result is kept and shown the next time you open AI Studio on this Function Unit.',
    resultDocs:
      'When the design is applied, the description is saved as a new version of Requirements with the source AI Studio · one-click generation. If Requirements already had text, the description is added at the end under Additional requirements and the earlier text stays.',
    resultFigure:
      'Result of One-click generate on Purchase Request. Right: the Proposed change card grouped by phase, marked Applied. Middle: Process Design reloaded with the generated flow.',
    catStop: 'Replaces Propose change while a round runs. Cancels the round. The design is not changed.',
    catCard:
      'Result card. One group per phase with the generated items: new N for added items, replaces N existing where the round replaces what was there.',
    catPreCheck: 'Problems found before writing. Orange warnings do not stop the design from being applied. Red errors do.',
    catApplied: 'The design on the card is already written to the Function Unit. The designers in the middle have been reloaded.',
    catApply:
      'Shown when the design was generated but not written. Click it to write the design yourself. A confirmation names how many existing items it replaces.',
    catFix:
      'Shown when the card lists errors. Sends the errors back and runs the whole generation again. The fix request is not saved to Requirements.',
    catConfirmPhase: 'After reviewing a phase, click it to mark the phase done and move to the next one.',
    failTitle: 'When it fails',
    failNoInput: 'Generate is disabled: the box is empty and the Function Unit has no Requirements document. Type a description or import the document first.',
    failCredentials: 'AI credentials are missing, please sign in again: sign out and in, then open Build with AI again. The dialog stays open and nothing is submitted.',
    failErrors:
      'The card lists errors and reads Apply would be rejected — revise the request and propose again: the design was not written. Click Let AI fix it, or go back to Build with AI and describe the requirements more precisely.',
    failAutoApply:
      'The card carries a warning that the design was generated but could not be applied automatically, often because someone else was applying an AI change at that moment. Click Apply on the card.',
    failGeneration: 'A red message in AI Copilot instead of a card: the round failed and the design is unchanged. Open Build with AI and generate again.',
    failUndo: 'One-click generate has no Undo. To go back, roll back to an earlier version in Version Management.',
    failNotGenerated: 'View Design, Connections, Email Templates, Email Monitors and Automation stay empty after the round. That is expected.',
  },
}
