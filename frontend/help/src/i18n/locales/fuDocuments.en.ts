export default {
  fuDocumentsGuide: {
    pageTitle: 'Requirements and Function Unit Design',
    crumb: 'Developer Workstation · Function Units · Function Unit Settings',
    intro:
      'Every Function Unit keeps two Markdown documents: Requirements and Function Unit Design. You edit them in Function Unit Settings, and each Save adds a version. AI Studio reads both documents and updates them after you confirm a phase (see [[/ai-studio]]).',
    flowTitle: 'Order of work',
    flow1: 'Open the Function Unit and click Function Unit Settings',
    flow2: 'Open the Requirements tab or the Function Unit Design tab',
    flow3: 'Type Markdown in the left pane, or click Import and pick a file',
    flow4: 'Check the rendered text in the right pane',
    flow5: 'Click Save. The version line shows the new version number',
    whatTitle: 'What the two documents are',
    whatBody:
      'Requirements holds what the business asked for. Function Unit Design holds how the Function Unit answers it: process, tables, forms, views, actions. Both are plain Markdown text stored with the Function Unit, one version per Save. The Basic Info tab of the same dialog is not versioned.',
    editorFigure:
      'Function Unit Settings, Requirements tab. Left: Markdown text after Import, marked Unsaved. Right: rendered preview. Toolbar: Edit, Preview, Import, Download, Version History, Save.',
    editorTitle: 'Editor toolbar',
    editorBody:
      'The Requirements tab and the Function Unit Design tab have the same toolbar. If you leave a tab with unsaved text, the dialog asks before it discards the text.',
    catEdit: 'Two panes: Markdown on the left, rendered text on the right. This is the default.',
    catPreview: 'Rendered text only. Read-only members always see this layout.',
    catImport:
      'Pick a file and load its text into the editor. Nothing is stored until you click Save. Hidden for read-only members.',
    catDownload: 'Save the text the editor shows as a .md file. Disabled while the editor is empty.',
    catHistory: 'Open the version list of this document. Disabled until the first version exists.',
    catSave:
      'Store the editor text as the next version. Disabled when the text equals the latest version. Hidden for read-only members.',
    catUnsaved: 'Shown when the editor text differs from the latest version.',
    catVersionLine:
      'Latest version, who saved it, when, and where it came from. The number before the dot goes up when someone starts a new AI design in AI Studio. The number after the dot goes up on every Save.',
    catEmpty: 'Shown instead of the version line when this document has never been saved.',
    importTitle: 'Import and download a file',
    importBody:
      'Import replaces the text in the editor with the text of the file. Review it in the right pane, then click Save to keep it as a new version. If the editor holds unsaved changes, a dialog asks first. Earlier saved versions stay in Version History.',
    downloadBody:
      'Download writes what the editor shows at that moment, unsaved changes included. The file name carries the version the text is based on.',
    catFileTypes: 'File types Import accepts. The size limit is 200 KB. Line endings are normalised.',
    catReplace:
      'Button in the dialog that asks Replace your unsaved changes with the content of the file. Cancel keeps your text.',
    catFileName: 'Download file name: Function Unit name, document type (requirements or design), version.',
    historyTitle: 'Version History',
    historyBody:
      'The list shows every version, newest first, with the saver, the time and the source. Restore does not delete anything. It adds a new version with the old text.',
    historyFigure: 'Version History of Requirements. Each entry: version, saver and time, source, then View, Compare with current, Restore.',
    catCurrent: 'Tag on the latest version. That entry offers View only.',
    catView: 'Show the text of that version below the list.',
    catCompare: 'Open a line-by-line comparison between that version and the current one.',
    catRestore: 'Add a new version with the text of that one, after a confirmation. Hidden for read-only members.',
    srcManual: 'Source: saved from this editor, typed or imported from a file.',
    srcImported: 'Source: came with a Function Unit package that was imported.',
    srcCloned: 'Source: copied when the Function Unit was cloned.',
    srcRestored: 'Source: created by Restore from the named version.',
    srcRollback: 'Source: written back by a Version Management rollback to the named Function Unit version.',
    srcAiSync: 'Source: AI Studio updated the document after the named phase was confirmed.',
    srcAiFull: 'Source: AI Studio checked the whole design against the document (Documents, Check now).',
    srcOneClick: 'Source: the description typed for One-click generate. See [[/ai-studio#one-click]].',
    travelTitle: 'Export, clone and rollback',
    travelBody:
      'Export puts both documents into the Function Unit package, and importing the package adds them as a new version marked Imported. Clone copies the latest text to the new Function Unit. A Version Management rollback writes the documents of that Function Unit version back as a new version. A package exported before documents existed leaves the documents unchanged.',
    failTitle: 'When it fails',
    failType: 'Only Markdown or plain text files (.md, .markdown, .txt) can be imported: the file has another extension. Convert a Word or PDF file to Markdown first.',
    failSize: 'The file is larger than 200 KB, or on Save: The document is larger than 200 KB. Shorten the text.',
    failEmptyFile: 'The file is empty: the file holds no text. The editor keeps its text.',
    failConflict:
      'Document changed: someone saved a newer version while you were editing. Load latest discards your text. Save anyway stores your text as the next version on top of theirs.',
    failReadOnly: 'Read-only members see Preview, Download and Version History. Import, Save and Restore are hidden.',
  },
}
