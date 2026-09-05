import formEventMessages from './formEvents.en'

export default {
  app: {
    name: 'Guidelines',
    tagline: 'How formulas, views, and designers behave — same text in every environment.',
    home: 'All guidelines',
    navAria: 'All guidelines',
    flowAria: 'Order of work',
    relatedTitle: 'Related guidelines',
    langAria: 'Language',
    langEn: 'English',
    langZhCn: '简体中文',
    langZhTw: '繁體中文',
    jumpAria: 'Jump to a task',
    howToDefault: 'Default',
    howToResult: 'After you run the sample',
    howToNote: 'Note',
  },
  home: {
    title: 'Guidelines',
    intro:
      'Topics sit under the same menus as Developer Workstation, Admin Center, and User Portal. Grey names in the sidebar are product menus with no article yet. No sign-in.',
    byNeedTitle: 'Start from the job',
    byNeedIntro: 'Each article is a designer job: how to do it, exact fields, then what fails.',
    emptyPortal: 'No article under this portal yet. The sidebar still lists its menus.',
    hint: {
      dw: 'Matches the Developer Workstation sidebar, then the tabs inside a Function Unit.',
      admin: 'Matches the Admin Center sidebar. System Config is in the user menu there.',
      portal: 'Matches the User Portal sidebar sections.',
    },
  },
  nav: {
    dw: 'Developer Workstation',
    functionUnits: 'Function Units',
    processDesign: 'Process Design',
    tableDesign: 'Table Design',
    formDesign: 'Form Design',
    formEventsHub: 'How to write events',
    formControls: 'Controls',
    formMenuBasic: 'Basic',
    formMenuExtend: 'Extend',
    formMenuMi: 'MI',
    formMenuLayout: 'Layout',
    formMenuAide: 'Auxiliary',
    formControlEvent: 'Event',
    formCtlInput: 'Input',
    formCtlTextarea: 'Textarea',
    formCtlPassword: 'Password',
    formCtlInputNumber: 'InputNumber',
    formCtlRadio: 'Radio',
    formCtlCheckbox: 'Checkbox',
    formCtlSelect: 'Select',
    formCtlSwitch: 'Switch',
    formCtlSlider: 'Slider',
    formCtlRate: 'Rate',
    formCtlDate: 'Date',
    formCtlDateRange: 'DateRange',
    formCtlTime: 'Time',
    formCtlTimeRange: 'TimeRange',
    formCtlCascader: 'Cascader',
    formCtlColorPicker: 'ColorPicker',
    formCtlUpload: 'Upload',
    formCtlTree: 'Tree',
    formCtlTreeSelect: 'TreeSelect',
    formCtlTransfer: 'Transfer',
    formCtlEditor: 'Editor',
    formCtlSubTable: 'Sub-Table',
    formCtlInlineForm: 'Inline Form',
    formCtlLinkForm: 'Link Form',
    formCtlLookup: 'Lookup',
    formCtlOwner: 'Owner',
    formCtlRecordNote: 'Record Note',
    formCtlMiAssignment: 'Assignment Mode',
    formCtlRow: 'Row',
    formCtlCol: 'Col',
    formCtlCard: 'Card',
    formCtlTabs: 'Tabs',
    formCtlTabPane: 'TabPane',
    formCtlCollapse: 'Collapse',
    formCtlCollapseItem: 'CollapseItem',
    formCtlTitle: 'Title',
    formCtlHtml: 'HTML',
    formCtlDivider: 'Divider',
    formCtlAlert: 'Alert',
    formCtlSpace: 'Space',
    formCtlButton: 'Button',
    formCtlTag: 'Tag',
    formCtlImage: 'Image',
    viewDesign: 'View Design',
    actionDesign: 'Action Design',
    fuAutomation: 'Automation',
    connections: 'Connections',
    emailTemplates: 'Email Templates',
    emailMonitors: 'Email Monitors',
    decisionDesign: 'Decision Design',
    versionManagement: 'Version Management',
    automation: 'Automation',
    admin: 'Admin Center',
    acDashboard: 'Dashboard',
    acUsers: 'User Management',
    acEntitlement: 'Entitlement Management',
    acOrganization: 'Organization',
    acVirtualGroup: 'Virtual Group',
    acRoles: 'Role & Permission',
    acFunctionUnit: 'Function Unit',
    acBi: 'BI Management',
    acBiRegistry: 'Dashboard Registry',
    acBiAssignment: 'Dashboard Assignment',
    acBiRbac: 'RBAC Mapping',
    acAudit: 'Audit Log',
    acAuditAdmin: 'Admin Center Audit Logs',
    acAuditPortal: 'User Portal Audit Logs',
    acRelationTables: 'Relation Tables',
    acTableStructure: 'Table Structure',
    acTableData: 'Table Data',
    acPieces: 'Automation Pieces',
    acFlowMigration: 'Automation Flow Migration',
    acSystemConfig: 'System Config',
    portal: 'User Portal',
    upHome: 'Home',
    upSectionTask: 'Task',
    upTodo: 'To Do',
    upTasksToClaim: 'Tasks to Claim',
    upCompleted: 'Completed Tasks',
    upSectionRequest: 'Request',
    upNewRequests: 'New Requests',
    upMyRequests: 'My Requests',
    upSectionAudit: 'Audit',
    upAllRequests: 'All Requests',
    upSectionData: 'Data',
    upRelationTables: 'Relation Tables',
    upViews: 'Views',
    upSectionSetup: 'Setup',
    upDelegations: 'Delegations',
    upProfileSetup: 'User Profile Setup',
  },
  guides: {
    computedFields: {
      title: 'Computed field formulas',
      summary: 'How computed columns fill themselves, which functions exist, and what happens when a formula fails.',
    },
    emailSend: {
      title: 'Send email',
      summary:
        'Outbound connection, email template, and Send Task — every field, what it means, and why a send can fail.',
    },
    emailMonitor: {
      title: 'Email Monitor',
      summary:
        'Inbound mailbox, monitor template, field extraction, and Start Event binding — every field, before Deploy.',
    },
    upTasksToClaim: {
      title: 'To Do — claim pool',
      summary:
        'Business-unit role requests live on To Do. Claim before you edit; Claim all takes every free request in batches; the selection bar Claims or Unclaims only checked rows. Unclaim all releases only your holds. Optional auto-claim on open is off by default. Leaders, BU Approvers, and System Administrators can force-release someone else’s hold.',
    },
    taskDelegate: {
      title: 'Delegate a task',
      summary: 'Hand this one To Do to a person or a BU+Role pair without changing Current Assignee.',
    },
    formUpload: {
      title: 'Form Design — Upload',
      summary: 'Upload fields accept multiple files by default. Set Max files to 1 for a single file.',
    },
    formEvents: {
      title: 'Form events',
      summary: 'How to write control scripts and Form event: parameters, values, required, lock, show/hide, options, errors, banners, lookup filter, focus, labels, hooks, and user.',
    },
    formEventsBasic: {
      title: 'Basic controls — events',
      summary: 'One change sample per Basic control; full api methods on How to write events.',
    },
    formEventsExtend: {
      title: 'Extend and MI — events',
      summary: 'One sample per Extend / MI control; Lookup filter links to the events hub.',
    },
    formEventsLayout: {
      title: 'Layout and Auxiliary — events',
      summary: 'One click sample per Layout / Auxiliary control; visibility and labels on the hub.',
    },
  },
  computedFieldGuide: {
    pageTitle: 'Computed field formulas',
    crumb: 'Developer Workstation · Function Units · Table Design',
    intro:
      'A computed column fills itself. People cannot type into it on forms. Saving a record uses the server formula engine. Calculate in the Formula dialog is a preview only. The screenshot is the Purchase Request main table (help_pr).',
    flowTitle: 'Order of work',
    flow1: 'Open Table Design and the table',
    flow2: 'Tick Computed on the column',
    flow3: 'Open Formula and write the expression',
    flow4: 'Save the table',
    basicsFigure: 'Purchase Request (help_pr). Computed columns: window_days, line_count, grand_total, over_budget.',
    basicsTitle: 'How a computed column works',
    basicsBody:
      'Tick Computed, open Formula, write an expression, then save the table. The result type must match the column Data Type. A number formula on a text column will not save until you change Data Type to INTEGER or DECIMAL. A text formula on a number column needs VARCHAR, or a different formula.',
    thisRowTitle: 'This row',
    thisRowBody: 'Use field names from the same table. Unqualified names always mean this row.',
    thisRowSample: 'line_total on help_pr_line',
    relatedRowsTitle: 'Related rows (main table only)',
    relatedRowsBody:
      'On the main table, Related rows can total or count a sub-table. These functions are not available on a sub-table formula.',
    sampleSum: 'sum of line_total on the Line items sub-table',
    sampleAvg: 'average of line_total',
    sampleMinMax: 'smallest or largest line_total',
    sampleCountRows: 'number of line-item rows',
    sampleCountCells: 'number of non-blank line_total cells',
    parentTitle: 'Sub-table reads the main table',
    parentBody:
      'On a sub-table row formula, table.column reads one column from this Function Unit’s main table. The table name must be this FU’s MAIN table. The column must exist and must not itself be computed. Do not wrap it in SUM. On the main table, write the field name, or use SUM(table.column) for related rows.',
    parentSample: 'request_title on the help_pr main table',
    datesTitle: 'Dates',
    datesBody:
      'Whole calendar days only. Year-first dates are accepted: YYYY-MM-DD, YYYY/MM/DD, YYYY.MM.DD (month and day may be one or two digits; a time suffix is ignored). Ambiguous dates such as 02/06/2026 stay TYPE_MISMATCH.',
    dateMinus: 'need-by date minus start date (window_days)',
    dateDatediff: 'Power Fx order: end minus start',
    logicTitle: 'Conditions',
    logicBody:
      'IF, AND, OR, SWITCH, and COALESCE only evaluate the branches they need, so a failing unused branch does not fail the formula.',
    fnIf: 'over_budget: Y when grand_total is above 5000',
    fnAndOr: 'true only if every / any argument is true',
    fnSwitch: 'first matching pair wins; a trailing odd argument is the default',
    fnCoalesce: 'first non-blank argument',
    mathTitle: 'Numbers',
    mathBody:
      'ROUND, ROUNDUP, ROUNDDOWN, TRUNC, ABS, INT, SQRT, POWER (whole-number exponent only, so client and server match), MOD. Operators + − * / and comparisons.',
    textTitle: 'Text',
    textBody:
      'LEN, CONCAT, TRIM, UPPER, LOWER, LEFT, RIGHT, MID (1-based start; omit count to take the rest), SUBSTITUTE, FIND (1-based; blank if missing), STARTSWITH, ENDSWITH, VALUE (the only way to parse text as a number), ISBLANK.',
    errorsTitle: 'If the formula fails',
    errorsBody:
      'Block save shows #ERR and does not save the record. Leave blank saves the rest of the form with this column empty.',
    relationTitle: 'Relation Tables',
    relationBody:
      'Admin Center Relation Tables are standalone. They have no MAIN/SUB parent lookup and no related-row functions such as SUM.',
  },
  emailSendGuide: {
    pageTitle: 'Send email',
    crumb: 'Developer Workstation · Function Units · Connections / Email Templates / Process Design',
    intro:
      'Outbound mail needs three pieces: a send connection, an Email Template, and a Send Task on the process. Subject and body live on the template, not on the node. Each screen below lists every control: what it is, required or optional, and what blank does.',
    flowTitle: 'Order of work',
    flow1: 'Create an Outbound connection',
    flow2: 'Write an Email Template and enable it',
    flow3: 'Put a Send Task on the process',
    flow4: 'Select To, Connection, and Template, then save',
    connectionFigure: 'Connections: Custom SMTP (PR Notify SMTP) and Gmail. Emails are masked in the figure.',
    templateFigure: 'Email Templates: PR Approved Notice. Subject uses the request title field.',
    bodyFigure: 'PR Approved Notice: Visual / HTML, preview on the right. Insert Variable for request_title and grand_total.',
    sendTaskFigure:
      "Process Design: Send task selected. Click {'{'} {'}'} beside To to insert a main-table field as ${\'{'}fieldName{\'}'}.",
    connectionTitle: 'First: an Outbound connection',
    connectionBody:
      'Open Connections. Set Direction to Outbound (send). Fill the sender address, SMTP username, and password. Host, port, and Use TLS come from Admin Center → System Config. Use Test to send a trial message before you wire the process. The same email cannot have two connections in the same direction — edit the existing row or pick Inbound instead.',
    connectionCatalogLead: 'Field catalog — every control on New / Edit Connection (Outbound) and Test.',
    fDirection:
      'Required. Outbound (send) for this job; Inbound (monitor) is for Email Monitor. A legacy Both row must be saved as one of those two.',
    fDirectionOutbound: 'Choice: this connection sends mail (SMTP). Pick this before you fill sender and SMTP login.',
    fDirectionInbound:
      'Choice: this connection only polls a mailbox (IMAP). Do not pick this for Send Task. See Email Monitor.',
    fFromEmail:
      'Required. Outbound From address; also used as the connection name. SMTP login goes in Username, not here. Blank blocks save.',
    fFromName:
      'Optional display name in the From header (for example Hermes Notify). Blank uses only the sender email.',
    fSmtpUsername:
      'SMTP login (service account). Usually different from the sender email. Leave blank only for an anonymous relay that does not use SMTP AUTH.',
    fSmtpPassword:
      'Required when Username is set (new row). SMTP password or provider app password. On edit, leave blank to keep the stored password.',
    fSmtpHost:
      'Not typed here. SMTP hostname comes from Admin Center → System Config (Outbound connections).',
    fSmtpPort:
      'Not typed here. Common 25/587 (STARTTLS) or 465 (SSL). Set with Use TLS in Admin Center → System Config.',
    fUseTls:
      'Not typed here. Yes for SSL on 465 or STARTTLS on 25/587. Set in Admin Center → System Config.',
    fProvider:
      'List column (Gmail, Outlook / Office 365, Yahoo Mail, QQ Mail, 163 Mail, Custom SMTP). Identifies the row; host still comes from System Config.',
    fConnEnabled:
      'Switch. Off: Send Task cannot use this connection at runtime. Turn on after Test succeeds.',
    fTest: 'Opens Test Connection. Use before wiring a Send Task so a bad password fails here, not on a live run.',
    fTestRecipient: 'Required in the Test dialog. Address that receives the trial message. Blank blocks send.',
    fSendTest: 'Sends one trial message using this outbound connection and the test recipient.',
    templateTitle: 'Write an Email Template',
    templateBody:
      'Open Email Templates. Give the template a name, write Subject and Body, then tick Enabled. Use Insert Variable for a main field, a Lookup or Related attribute, or a sub-table (rendered as a table). Do not type quoted field names.',
    templateCatalogLead: 'Field catalog — every control on New / Edit Template.',
    templateNameSample: 'Template name in the screenshot (Purchase Request demo).',
    fTemplateName: 'Required. Name in the Send Task Template list. Blank blocks save.',
    fTemplateSubject:
      "Required at send time. Use Insert Variable (same tokens as Body). Do not type $\'name\' or quoted field names. If every token is empty at send, the run fails.",
    fInsertVariable:
      'Dropdown. Pick a main field, a Lookup / Related attribute, or a sub-table (rendered as a table in the body). Inserts a token; do not wrap the name in quotes.',
    fTemplateBody:
      'HTML of the message. Compose in Visual or HTML below. Empty body still sends if Subject is non-empty after variables.',
    bodyTitle: 'Visual and HTML',
    bodyBody:
      'Switch Visual and HTML as needed. Email preview on the right is for design time only. HTML mode keeps <style> tags so class-based table CSS shows in the preview; stay in HTML to author that markup. Switching from HTML back to Visual may simplify tables, <style> tags, and inline styles — confirm the dialog if you still need the rich editor.',
    bodyCatalogLead: 'Field catalog — body editor and Enabled on the same template dialog.',
    fBodyVisual: 'Rich editor. Default for most notices. Insert Variable is on the toolbar.',
    fBodyHtml:
      'Source editor. Keeps <style> and class selectors in the design-time preview. Confirm if you switch back to Visual.',
    fEmailPreview: 'Design-time only. Tokens stay visible; it is not a live send.',
    fTemplateEnabled:
      'Required for Send Task. Off: the template stays in the list but send fails until you turn it on.',
    sendTaskTitle: 'Put a Send Task on the process',
    sendTaskBody:
      "To, Connection, and Template are required. For To, From, Cc, Bcc, and Reply-To, type an email or click {'{'} {'}'} to pick a main-table field (${\'{\'}fieldName{\'}'}). Subject and body come from the selected template — not from the recipient fields. Email sends when the flow reaches this node (complete any user task before it first).",
    sendTaskCatalogLead:
      'Field catalog — Send Task Config (required row) and Basic Info. Advanced options are in the next section.',
    fTo:
      "Required. One address, semicolon-separated addresses, or ${\'{'}fieldName{\'}'} from the main table (for example ${\'{'}to{\'}'}). Do not put the subject here. Blank: send fails.",
    fInsertField:
      "Insert field. Opens a searchable list. Picks a main-table field as ${\'{'}fieldName{\'}'} only — not Lookup or sub-table tokens. Also available on From, Cc, Bcc, and Reply-To.",
    fInsertSearch: 'Filters the insert list. Empty result: No matching fields.',
    fProcessVars: 'Group in the insert list for process variables (not main-table columns).',
    fInitiator:
      "Process variable. Inserts ${\'{'}initiator{\'}'} (the user who started the instance). At send time the engine treats it as a user id and looks up that user's email; if lookup fails, send fails.",
    fSendConnection:
      'Required. Outbound connection from Function Unit → Connections. Empty or inbound: send fails.',
    fFromOverride:
      'Optional. Overrides the From address on the selected connection. Blank: connection Sender Email is used.',
    fSendTemplate:
      'Required. Subject and body are taken from this Email Template at send time. Missing or disabled: send fails.',
    fTaskId: 'Read-only BPMN id (for example Activity_…). Set when you save the diagram.',
    fTaskType: 'Read-only. Shows Send Task.',
    fTaskName: 'Optional label on the diagram. Blank keeps the default name.',
    extraTitle: 'Cc, Bcc, and attachments',
    extraBody:
      "Open Show advanced options on the Send Task. Cc and Bcc accept semicolon-separated addresses or ${\'{'}fieldName{\'}'}; use {'{'} {'}'} on those fields too. For attachments, pick a FILE / Upload field. Sensitivity, Importance, and Reply-To are optional.",
    extraCatalogLead: 'Field catalog — Show advanced options on Send Task Config.',
    fShowAdvanced: 'Reveals Cc, Bcc, Attachments, Sensitivity, Reply-To, and Importance. Hide when you are done.',
    fCc:
      "Optional extra recipients (visible to others). Semicolon-separated addresses or ${\'{'}fieldName{\'}'}. Blank: no Cc.",
    fBcc:
      "Optional hidden extra recipients. Same format as Cc. Blank: no Bcc.",
    fAttachments:
      'Optional. Files uploaded to the chosen FILE field at runtime are attached. Empty list: add an Upload field on the form, then reopen this panel.',
    fUploadField:
      'One FILE / Upload field per row: main table, sub-table, or Lookup target. Not a FILE field: send fails.',
    fAddAttachment: 'Adds another Upload field row. Disabled when no FILE fields exist on the forms.',
    fRemoveAttachment: 'Deletes that attachment row. Does not delete files already stored on records.',
    fSensitivity: 'Optional header. Choices: Normal, Personal, Private, Confidential. Default Normal.',
    fReplyTo:
      "Optional reply address (email or ${\'{'}fieldName{\'}'}). Blank: replies go to From.",
    fImportance: 'Optional header. Choices: Low, Normal, High. Default Normal.',
    failTitle: 'When send fails',
    failBody: 'Fix the template or connection, then start a new process (Deploy does not change running instances).',
    failConnection: 'Connection missing, inbound, or disabled.',
    failTo: 'To is empty after variables fill in.',
    failTemplate: 'Template missing or Enabled is off.',
    failSubject: 'Whole Subject is empty after variables fill in.',
    failAttachment: 'Attachment is not a FILE field, or the file cannot be downloaded.',
    failSmtp: 'Admin Center has no global SMTP (host / port / TLS) for outbound.',
    failRunning:
      'You completed a user task after the Send Task, or this instance started before Deploy. Complete the user task before the Send Task, then start a new instance.',
  },
  emailMonitorGuide: {
    pageTitle: 'Email Monitor',
    crumb: 'Developer Workstation · Function Units · Email Monitors / Process Design',
    intro:
      'Email Monitor starts a process when mail arrives. Create an inbound connection, a monitor template (mailbox plus extraction), then bind that template on a Start Event. Each screen below lists every control: what it is, required or optional, and what blank does.',
    flowTitle: 'Order of work',
    flow1: 'Create an Inbound connection',
    flow2: 'Create a monitor template',
    flow3: 'Bind sample text to main-table fields',
    flow4: 'Turn on Inbound Email Trigger on the Start event',
    flow5: 'Save the Start Event binding, then Deploy',
    inboundFigure: 'Edit Connection: Direction Inbound (monitor). Mailbox and IMAP login; host comes from System Config.',
    templateFigure: 'Email Monitors: Vendor quote to PR, inbound mailbox, poll every 60 seconds.',
    startEventFigure: 'Start event StartEvent_Email: Vendor quote to PR bound, Subject Filter Quote, extraction configured.',
    inboundTitle: 'First: an Inbound connection',
    inboundBody:
      'Open Connections. Set Direction to Inbound (monitor). Fill the mailbox address and IMAP username and password. IMAP host, port, and SSL come from Admin Center → System Config. There is no inbound connection until Direction is Inbound.',
    inboundCatalogLead: 'Field catalog — every control on New / Edit Connection (Inbound).',
    fDirection: 'Required. Must be Inbound (monitor) or this mailbox never appears under Email Monitors.',
    fDirectionInbound: 'Choice: poll this mailbox with IMAP. Outbound (send) is for Send email, not this page.',
    fMailboxEmail:
      'Required. Mailbox to poll; also used as the connection name. Blank blocks save.',
    fImapUsername:
      'IMAP login (service account). Usually different from the mailbox address. Required with password for a new row.',
    fImapPassword:
      'IMAP password or provider app password. Required on create when username is set. On edit, leave blank to keep the stored password.',
    fImapHost:
      'Not typed here. IMAP hostname comes from Admin Center → System Config (Inbound connections).',
    fImapPort:
      'Not typed here. Common 993 (SSL) or 143 (STARTTLS/plain). Set in Admin Center → System Config.',
    fImapSsl:
      'Not typed here. Yes: imaps, usually port 993. No: plain or STARTTLS imap, usually port 143. Set in System Config.',
    fConnEnabled:
      'Switch on the connection. Off: Email Monitor cannot poll this mailbox. Turn on before Deploy.',
    templateTitle: 'Create a monitor template',
    templateBody:
      'Open Email Monitors. Set the rule name, inbound mailbox, folder, poll interval (seconds; minimum 30), and System Initiator. Tick “Send to manual review when required fields are missing” if incomplete mail should wait for a person. Do not set From or Subject filters here, and do not set Process Key on the template.',
    templateCatalogLead:
      'Field catalog — New / Edit Monitor. Rows marked not on this form belong only on the Start Event.',
    templateSample: 'Typical folder / label in the screenshot.',
    templateNameSample: 'Monitor template name in the screenshot (Purchase Request demo).',
    fRuleName: 'Required. Name in the Start Event Email Monitor list. Blank blocks save.',
    fInboundMailbox:
      'Required. Inbound connection created under Connections. Empty list: set a connection Direction to Inbound first.',
    fSystemInitiator:
      'Optional. User recorded as initiator of processes started from mail. Search by name, username, or email. Blank: platform default initiator.',
    fFolder:
      'IMAP folder or label to poll. Placeholder INBOX. Blank is stored as INBOX.',
    fPoll:
      'Seconds between polls. Minimum 30; step 30. Lower values are rejected. Typical sample: 60.',
    fReview:
      'Optional. On: mail that is missing a Required extraction field waits for a person. Off: that mail is skipped.',
    fMonitorEnabled:
      'List column only (Yes / No). New monitors start enabled. There is no Enabled switch on the create/edit dialog.',
    fProcessKeyNotHere:
      'Not on this form. The process key is filled automatically on the Start Event (Process Key (auto)).',
    fFromNotHere:
      'Not on this form. Hint on the dialog: From / Subject filters are configured on each Start Event, not here.',
    fSubjectNotHere:
      'Not on this form. Set Subject Filter on the Start Event only.',
    extractSample: 'Main-table field filled from the email subject (Purchase Request demo).',
    extractTitle: 'Field extraction (no code)',
    extractBody:
      'Paste a real sample subject and plain-text body. Select a value, then Bind selection to a main-table field. Mark Required as needed. Optional HTML body is for tables: map one HTML table to a sub-table (one email row per record). Add a Sub-Table on the main process form first if the binding list is empty.',
    extractCatalogLead: 'Field catalog — Field Extraction (no code) on the monitor dialog.',
    fSampleTab: 'First tab. Holds the sample message you bind from. Not sent anywhere.',
    fSampleSubject: 'Paste a real sample subject, then select text to bind. Blank: subject mappings have no preview.',
    fSampleFrom: 'Optional sample From line for Header mappings. Blank: From-based rules preview empty.',
    fSampleText:
      'Paste the plain-text body, then select a value and Bind selection. Blank: text mappings have no preview.',
    fSampleHtml:
      'Optional HTML for table mapping. Blank: Sub-table (HTML table) has nothing to map.',
    fFieldMapping: 'Second tab. Rows that copy values from the sample into main-table fields.',
    fAddField: 'Adds an empty mapping row. Then set Target Field, Source, Method, and Rule.',
    fBindSelection:
      'Writes the highlighted sample text into the selected mapping (before/after or label). Does nothing if nothing is selected.',
    fTargetField:
      'Required per row. Main-table column to fill. Empty list: the main table has no mappable fields.',
    fSource:
      'Where to read. Choices: Subject; Text + HTML (recommended); Text + HTML; HTML only; Header; Constant.',
    fMethod:
      'How to cut the value. Choices: LABEL, BETWEEN, REGEX, CONST, HEADER (shown as those codes).',
    fRule:
      'Depends on Method: LABEL uses a label such as Case No; BETWEEN uses before text and after text; REGEX uses a pattern; CONST uses a fixed value; HEADER uses a header name such as From. Blank: preview stays empty.',
    fRequired:
      'On: missing value follows the manual-review checkbox. Off: the field may stay empty and the process can still start.',
    fPreview: 'Read-only. Shows what the rule extracts from the sample. Empty means the rule does not match yet.',
    fSubTableTab: 'Third tab. Maps one HTML <table> in the email to a form Sub-Table (one email row per record).',
    fAddSubTable:
      'Adds a sub-table block. Disabled when the process form has no Sub-Table binding — add a Sub-Table on the main form first.',
    fSubBinding: 'Which form Sub-Table this HTML table fills. Required per block.',
    fTableIndex:
      'Which HTML table in the email, 0-based (0 is the first table). Wrong index maps the wrong grid.',
    fTableSelector: 'Optional CSS selector. Blank: all <table> elements, then Table # picks among them.',
    fHeaderRow: 'On: first row is column titles, not data. Off: first row is the first record.',
    fAddColumn: 'Adds a column mapping (column number → Target Field).',
    fColumnIndex: '0-based column in that HTML table. Must match the sample table.',
    startEventTitle: 'Bind it on the Start Event',
    startEventBody:
      'In Process Design, open Inbound Email Trigger and tick Start process when email arrives. Select the monitor template. From Filter and Subject Filter belong only on this Start Event. Save Start Event binding. If the start event has no ID, save the diagram first. If Field extraction says none, edit the template under Email Monitors.',
    startEventCatalogLead: 'Field catalog — Inbound Email Trigger on the Start event.',
    subjectFilterSample: 'Subject Filter on the Start Event only, not on the template (screenshot uses Quote).',
    fPanelTitle: 'Properties heading. Open this when the Start event is selected.',
    fEnable:
      'On: this Start event starts a process when matching mail arrives. Off: the bound template is ignored until you tick it again. Select a monitor template below.',
    fBoundProcess: 'Read-only. Filled from Process ID in process properties. Empty: save the process properties first.',
    fBoundEvent: 'Read-only. BPMN id of this Start event. Empty: save the diagram first, then bind.',
    fSelectTemplate:
      'Required when the trigger is on. Monitor template from Email Monitors. Empty list: create a template first.',
    fStartMailbox: 'Read-only. Mailbox of the selected template. Change it under Email Monitors, not here.',
    fExtractionStatus:
      'Read-only. Extraction configured, or No extraction rules — edit the template under Email Monitors.',
    fFromFilter:
      'Optional. Only mail whose From contains this text. Blank: any sender. Not set on the Email Monitors template.',
    fSubjectFilter:
      'Optional. Only subjects containing this text (screenshot: Quote). Blank: any subject. Not set on the template.',
    fSaveBinding:
      'Writes the trigger, template, and filters. Does not Deploy. Failures: no Start Event ID, no process key, no template.',
    deployTitle: 'Before Deploy',
    deployBody:
      'Every enabled monitor template must be bound to a Start Event. The mailbox connection must be Enabled, Direction Inbound, with username and password filled. Enable the connection before using it for Email Monitor.',
    failUnbound: 'An enabled monitor template is not bound to any Start Event.',
    failDisabledConn: 'The mailbox connection is disabled, not Inbound, or username / password is missing.',
    failNoId: 'Start event has no ID — save the process diagram first.',
    failNoProcessKey: 'Process key not found — set Process ID in process properties.',
    failNoExtraction: 'Field extraction says none — edit the template under Email Monitors.',
    deleteTitle: 'Deleting a template',
    deleteBody:
      'A template bound to one or more Start Events cannot be deleted. Unbind it in Process Design first, then delete the template.',
  },
  upTasksToClaimGuide: {
    pageTitle: 'To Do — claiming role requests',
    crumb: 'User Portal · Task · To Do',
    intro:
      'To Do lists requests assigned to you and business-unit role requests for your role. Role requests that nobody holds yet, that you hold, and that a colleague holds all stay on this one list. You must Claim a free role request before you can edit or submit it. Assignment Type uses a different colour for each category. Claim all claims every request that is free for you. Unclaim all releases only the role requests you hold. Auto-claim on open, off by default, claims a free role request when you open it from this list.',
    flowTitle: 'Order of work',
    flow1: 'Open To Do',
    flow2: 'Find a role request with an empty Claimed By cell, or click Claim all',
    flow3: 'Click Claim (or wait for Claim all), then edit and submit',
    flow4: 'Use Unclaim or Unclaim all if you need to let another role member take it',
    listTitle: 'What the list shows',
    listBody:
      'Open User Portal → Task → To Do. Direct-user, department-role, and delegated requests appear with their Assignment Type colour. Role-pool requests use the BU + role colour. Claimed By is empty until someone holds a role request. After a claim, the column shows You for your own hold, or the other person’s name (Held). Click the Request ID to open the form.',
    claimTitle: 'Claim, Unclaim, Claim all, and Unclaim all',
    claimBody:
      'Claim locks the request to you. Other members of the role can only view it until you Unclaim or complete it. Completing the task does not need a second Claim if you already hold it. Claim all (top right) confirms once, then claims every free request in batches of 100 until none remain. After a search, tick rows and use Claim or Unclaim on the bar between “N selected” and Batch Urge: those buttons always show; they are disabled when none of the ticked rows can be claimed or released. The confirm dialog shows how many of the selection will be processed. Unclaim all confirms once, then releases every role request you hold, in the same batch size. Selection Unclaim and Unclaim all never force-release a colleague’s hold. If some rows fail, the toast shows claimed / skipped / failed counts.',
    claimSample: 'Claim on the row, or Claim all at the top right',
    unclaimSample: 'releases the hold; Claimed By becomes empty and another member can Claim it',
    claimedBySample: 'column on To Do for role-pool rows',
    claimAllSample: 'top-right button; one confirm, then automatic batches',
    unclaimAllSample: 'next to Claim all; releases only your holds, never a colleague’s',
    claimSelectedSample: 'on the selection bar after you tick rows; confirms “Claim N of M selected”',
    unclaimSelectedSample: 'next to selection Claim; only your holds in the ticks, never Force Unclaim',
    autoClaimTitle: 'Auto-claim on open',
    autoClaimBody:
      'The Auto-claim on open switch sits on the To Do top bar and on User Profile. It is stored with your account and defaults to off. When it is on, clicking a Request ID on To Do claims that row first if it is still free, then opens the form. There is no success toast. If the claim fails (for example someone else just took it), you still open the form and see an error. Home, notifications, email links, bookmarks, and Completed Tasks do not auto-claim.',
    autoClaimSample: 'switch on To Do and User Profile; default off',
    autoPreviewTitle: 'Auto-preview on open',
    autoPreviewBody:
      'The Auto-preview on open switch sits next to Auto-claim on To Do and on User Profile. It is stored with your account and defaults to off. When it is on, opening a task or My Request form opens the first previewable file after the form loads. Zip and similar types are skipped. Record Note attachments are not in this first-file list — click a note file to preview it. Process-form copies and Start Request drafts do not auto-preview.',
    autoPreviewSample: 'switch on To Do and User Profile; default off',
    filePreviewTitle: 'File preview',
    filePreviewBody:
      'Click a file name on the form, a sub-table cell, or a note attachment to open preview in a new browser tab, so the form stays visible. If the browser blocks the tab, preview stays in a dialog. Images and TIFF have Zoom in, Zoom out, 100%, and Fit. Fit is the default. Hold Ctrl (⌘ on Mac) and scroll the mouse wheel to zoom around the cursor. 100% shows the original pixels and uses scroll bars. TIFF ignores thumbnail IFDs so a scanned page is not shown as a tiny preview. When the request has several previewable files, Previous file and Next file stay in the same tab and walk main-table uploads, then each sub-table row, then nested sub-tables. Note attachments walk only that note’s files.',
    filePreviewSample: 'new tab; Ctrl + scroll zoom; Fit / 100%; Previous file / Next file',
    detailTitle: 'On the task page',
    detailBody:
      'If nobody holds the role request, or someone else holds it, the form is view-only and the action bar is hidden. The banner at the top says the request is not claimed yet, that you are holding it, or that another person claimed it. Only the holder sees Claimed by You and can Unclaim, edit, and submit. If you are a Leader of this role, a BU Approver of this business unit, or a System Administrator, the banner and the list also show Force Unclaim.',
    leaderTitle: 'Leader, Approver, and Admin',
    leaderBody:
      'Member and Leader are per business unit and role, not a platform-wide flag. A Member Claims and Unclaims only their own hold. A Leader of that same role can Force Unclaim a hold taken by someone else. The business unit Approver and a System Administrator (SYS_ADMIN) have the same Force Unclaim right. An Auditor cannot. Confirm before Force Unclaim: Claimed By becomes empty, and another Member can Claim it. Admin Center → User Management shows Member or Leader on each business unit role. Organization → Eligible Roles lists the Leaders of each role so you can find who can release a stuck hold.',
    applyTitle: 'Apply as Member or Leader',
    applyBody:
      'Open User Profile Setup → Apply Permission. Choose the business unit and role, then Member or Leader. The approver’s Approve Request dialog shows that Member or Leader choice and the role. If you already have that role as a Member, you can apply to become Leader. After approval, the User Profile Setup card and Admin user page show Leader on that role.',
    upgradeTitle: 'After this version: one To Do list',
    upgradeBody:
      'Tasks to Claim is no longer a separate menu. Free role requests, your holds, and colleague holds all appear on To Do. Assignment Type no longer offers Virtual Group as a filter; use BU + role for the claim pool. Older bookmarks to /tasks/to-claim open To Do.',
    failTitle: 'When Claim fails',
    failBody:
      'Claim fails if another member already holds the request, you are no longer in the role that was written when the task was created, or the engine is unavailable. Refresh the list: the Claimed By column shows the current holder. Unclaim fails if you are not the holder. Unclaim all skips colleague holds and reports skipped or failed counts. Force Unclaim fails if you are not a Leader of that role, a BU Approver of that business unit, or a System Administrator. Claim all continues after a failed row and reports the failed count at the end.',
  },
  taskDelegateGuide: {
    pageTitle: 'Delegate a task',
    crumb: 'User Portal · Task · To Do',
    intro:
      'You are Current Assignee. Delegate this one open task. You stay the assignee. Someone else can complete it on your behalf. This is not Transfer, and it is not Setup → Delegations standing rules.',
    flowTitle: 'Order of work',
    flow1: 'Open the task from To Do',
    flow2: 'Click Delegate on the action bar',
    flow3: 'Choose Specified user or Specified BU and Role',
    flow4: 'Confirm',
    flow5: 'The other person opens Delegated on To Do (match workspace for BU+Role)',
    openTitle: 'Open the dialog',
    openBody:
      'On task detail, click Delegate. The red question mark next to the dialog title opens this page. If nobody is assigned yet (unclaimed candidate pool), Delegate is hidden. Claim first so there is a Current Assignee.',
    openSample: 'button on the task action bar',
    userTitle: 'Specified user',
    userBody:
      'Tick Specified user. Click Target User (placeholder Click to search). A table lists Username, Display Name, Full Name, Email, and Employee ID; scroll sideways if the columns do not fit. Type to search those fields on the server, pick a row, then Confirm. You stay Current Assignee. An extra line reads Delegated to that person. They see On behalf of you. You can still complete the task yourself.',
    userRadioSample: 'first choice under Delegate to',
    userFieldSample: 'person who will complete on your behalf',
    userSearchSample: 'placeholder on Target User; type to search',
    buTitle: 'Specified BU and Role',
    buBody:
      'Tick Specified BU and Role. Pick Business Unit, then Role. Role stays disabled until a unit is selected. Both are required. People see and complete the task only when the header workspace is that same Business unit and Role pair. Switching workspace hides it. System Administrator does not see every delegated task. There is no Claim step after this kind of delegate.',
    buRadioSample: 'second choice under Delegate to',
    buFieldSample: 'first of the required pair',
    roleFieldSample: 'second of the required pair; disabled until a unit is chosen',
    afterTitle: 'After you confirm',
    afterBody:
      'A success toast says Delegated successfully. Current Assignee is still you. The extra line is Delegated to the person, or Delegated to the unit and role names. Transfer is a different button and still changes the assignee.',
    assigneeSample: 'still you after Delegate',
    delegatedToSample: 'extra line on Basic Info',
    seeTitle: 'Who sees it and can complete',
    seeBody:
      'The named user, or anyone whose current workspace matches that BU and Role, finds the task under To Do → Delegated. They complete it as On behalf of you. Anyone else has no permission. Switch workspace from Select workspace identity in the header when you were given a BU+Role target.',
    todoFilterSample: 'To Do filter for delegated work',
    onBehalfSample: 'what the other person sees on Basic Info',
    notTitle: 'What this is not',
    notBody:
      'Transfer changes Current Assignee. Setup → Delegations standing rules are a different page and do not use this dialog. Permission self-service requests are also different.',
    transferSample: 'reassign button on the same action bar',
    standingSample: 'Setup menu; standing rules, not this one task',
    failTitle: 'When it fails',
    failBody:
      'Confirm without a user shows Please select user. Confirm without both Business Unit and Role shows Please select both business unit and role. Delegating to yourself is rejected. A completed task cannot be delegated. An unclaimed pool task has no Delegate button.',
  },
  formUploadGuide: {
    pageTitle: 'Form Design — Upload',
    crumb: 'Developer Workstation · Function Units · Form Design',
    intro:
      'An Upload field on the form can take several files. Default is 10 files, 10MB each. Set Max files to 1 if the field must stay a single file. Saved JSON that still has Multiple off and Limit 1 was a generator default, not a designer choice — those fields also accept up to 10 until you set Max files. The properties panel shows Max files, Can not download, Readonly, and Advance (FileNet).',
    flowTitle: 'Order of work',
    flow1: 'Open Form Design and select an Upload field',
    flow2: 'Set Max files (default 10; 1 means a single file)',
    flow3: 'Optionally turn on Advance and fill FileNet header / repository mapping',
    flow4: 'Save the form, then check Preview or User Portal',
    maxTitle: 'Max files',
    maxBody:
      'On the Upload field properties, Max files is the cap. Default 10. Set 1 for a single file. Each file can be up to 10MB. At most 3 uploads run at once. The properties panel does not show Multiple or Maximum number of uploads allowed; Max files is the only cap. The field tip reads: Supported formats: jpg/png/pdf/docx/xlsx. Up to 10 files, 10MB each.',
    maxSample: 'the Max files number on the Upload properties panel',
    advanceTitle: 'Advance (FileNet)',
    advanceBody:
      'Advance is at the bottom of the Upload properties. It is off by default. Turn it on to store Header Info, Repository Detail, and document-property mapping on this field. Search Detail List, Retrieve Request Information, and Order By are shown as placeholders only. Tokens and host URLs do not belong on this panel. Advance does not hide the runtime detail block.',
    advanceSample: 'the Advance switch under Upload properties',
    runtimeTitle: 'What people see at runtime',
    runtimeBody:
      'Drop several files onto the dashed box, or click it and select several files in one go (Ctrl or Shift click in the file picker). User Portal and Form Preview keep the whole list. Under the upload control, each stored file shows File Description (editable while the form is writable), Callback URL (the platform file link), and Auto Send to FileNet (Completed until FileNet archive is connected). A sub-table add/edit dialog shows the same three fields. A sub-table list cell still shows the first file name and +N for the rest, for example report.pdf +2. If a companion filename column is configured on the Upload field, the original names are written there, joined with a semicolon and space. Send Email attachments from a FILE field include every stored file.',
    runtimeSample: 'report.pdf +2 on a sub-table cell',
    failTitle: 'When it fails',
    failBody:
      'Choosing more files than Max files shows Maximum {limit} files allowed. Zip files are not added to the in-form preview playlist. Each file still posts one at a time; a failed file does not remove the ones that already succeeded.',
  },
  ...formEventMessages,
}
