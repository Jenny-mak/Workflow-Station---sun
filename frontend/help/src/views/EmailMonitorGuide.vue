<template>
  <GuideArticle
    test-id="email-monitor-guide-page"
    page-title-key="emailMonitorGuide.pageTitle"
    intro-key="emailMonitorGuide.intro"
    crumb-key="emailMonitorGuide.crumb"
    flow-title-key="emailMonitorGuide.flowTitle"
    :flow-keys="flowKeys"
    :jump-links="jumpLinks"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, {
  type GuideJump,
  type GuideRelated,
  type GuideSection,
} from '@/components/GuideArticle.vue'

const flowKeys = [
  'emailMonitorGuide.flow1',
  'emailMonitorGuide.flow2',
  'emailMonitorGuide.flow3',
  'emailMonitorGuide.flow4',
  'emailMonitorGuide.flow5',
]

const jumpLinks: GuideJump[] = [
  { anchor: 'inbound', titleKey: 'emailMonitorGuide.inboundTitle' },
  { anchor: 'template', titleKey: 'emailMonitorGuide.templateTitle' },
  { anchor: 'extract-sample', titleKey: 'emailMonitorGuide.extractSampleTitle' },
  { anchor: 'extract-fields', titleKey: 'emailMonitorGuide.extractFieldMappingTitle' },
  { anchor: 'extract-attachments', titleKey: 'emailMonitorGuide.extractAttachmentsTitle' },
  { anchor: 'extract-subtable', titleKey: 'emailMonitorGuide.extractSubTableTitle' },
  { anchor: 'start-event', titleKey: 'emailMonitorGuide.startEventTitle' },
  { anchor: 'deploy', titleKey: 'emailMonitorGuide.deployTitle' },
]

const related: GuideRelated[] = [
  { to: '/email-send#connection', titleKey: 'guides.emailSend.title' },
  { to: '/computed-fields', titleKey: 'guides.computedFields.title' },
]

const catalog = { sampleLayout: 'block' as const }

const sections: GuideSection[] = [
  {
    anchor: 'inbound',
    titleKey: 'emailMonitorGuide.inboundTitle',
    bodyKey: 'emailMonitorGuide.inboundBody',
    bodyKeys: ['emailMonitorGuide.inboundCatalogLead'],
    figure: {
      src: 'guides/dw-connections-inbound.png',
      captionKey: 'emailMonitorGuide.inboundFigure',
    },
    ...catalog,
    samples: [
      { code: 'Direction', hintKey: 'emailMonitorGuide.fDirection' },
      { code: 'Inbound (monitor)', hintKey: 'emailMonitorGuide.fDirectionInbound' },
      { code: 'Mailbox Email', hintKey: 'emailMonitorGuide.fMailboxEmail' },
      { code: 'IMAP Username', hintKey: 'emailMonitorGuide.fImapUsername' },
      { code: 'IMAP Password', hintKey: 'emailMonitorGuide.fImapPassword' },
      { code: 'IMAP Host', hintKey: 'emailMonitorGuide.fImapHost' },
      { code: 'IMAP Port', hintKey: 'emailMonitorGuide.fImapPort' },
      { code: 'IMAP SSL', hintKey: 'emailMonitorGuide.fImapSsl' },
      { code: 'Enabled', hintKey: 'emailMonitorGuide.fConnEnabled' },
    ],
  },
  {
    anchor: 'template',
    titleKey: 'emailMonitorGuide.templateTitle',
    bodyKey: 'emailMonitorGuide.templateBody',
    bodyKeys: ['emailMonitorGuide.templateCatalogLead'],
    figure: {
      src: 'guides/dw-email-monitors.png',
      captionKey: 'emailMonitorGuide.templateFigure',
    },
    ...catalog,
    samples: [
      { code: 'Rule Name', hintKey: 'emailMonitorGuide.fRuleName' },
      { code: 'Inbound Mailbox', hintKey: 'emailMonitorGuide.fInboundMailbox' },
      { code: 'System Initiator', hintKey: 'emailMonitorGuide.fSystemInitiator' },
      { code: 'Folder / Label', hintKey: 'emailMonitorGuide.fFolder' },
      { code: 'Poll Interval (sec)', hintKey: 'emailMonitorGuide.fPoll' },
      { code: 'Send to manual review when required fields are missing', hintKey: 'emailMonitorGuide.fReview' },
      { code: 'Enabled', hintKey: 'emailMonitorGuide.fMonitorEnabled' },
      { code: 'Vendor quote to PR', hintKey: 'emailMonitorGuide.templateNameSample' },
      { code: 'INBOX', hintKey: 'emailMonitorGuide.templateSample' },
      { code: 'Process Key', hintKey: 'emailMonitorGuide.fProcessKeyNotHere' },
      { code: 'From Filter', hintKey: 'emailMonitorGuide.fFromNotHere' },
      { code: 'Subject Filter', hintKey: 'emailMonitorGuide.fSubjectNotHere' },
    ],
  },
  {
    anchor: 'extract-sample',
    titleKey: 'emailMonitorGuide.extractSampleTitle',
    bodyKey: 'emailMonitorGuide.extractSampleBody',
    bodyKeys: ['emailMonitorGuide.extractSampleCatalogLead'],
    figure: {
      src: 'guides/dw-email-extraction-sample.png',
      captionKey: 'emailMonitorGuide.extractSampleFigure',
    },
    ...catalog,
    samples: [
      { code: 'Sample Email', hintKey: 'emailMonitorGuide.fSampleTab' },
      { code: 'Subject', hintKey: 'emailMonitorGuide.fSampleSubject' },
      { code: 'From', hintKey: 'emailMonitorGuide.fSampleFrom' },
      { code: 'To', hintKey: 'emailMonitorGuide.fSampleTo' },
      { code: 'Cc', hintKey: 'emailMonitorGuide.fSampleCc' },
      { code: 'Reply-To', hintKey: 'emailMonitorGuide.fSampleReplyTo' },
      { code: 'Sent date', hintKey: 'emailMonitorGuide.fSampleDate' },
      { code: 'Message-ID', hintKey: 'emailMonitorGuide.fSampleMessageId' },
      { code: 'Plain Text Body', hintKey: 'emailMonitorGuide.fSampleText' },
      { code: 'HTML Body (optional, for tables)', hintKey: 'emailMonitorGuide.fSampleHtml' },
      { code: 'Bind selection', hintKey: 'emailMonitorGuide.fBindSelection' },
    ],
  },
  {
    anchor: 'extract-fields',
    titleKey: 'emailMonitorGuide.extractFieldMappingTitle',
    bodyKey: 'emailMonitorGuide.extractFieldMappingBody',
    bodyKeys: ['emailMonitorGuide.extractFieldCatalogLead'],
    figure: {
      src: 'guides/dw-email-field-mapping.png',
      captionKey: 'emailMonitorGuide.extractFieldMappingFigure',
    },
    ...catalog,
    samples: [
      { code: 'Field Mapping', hintKey: 'emailMonitorGuide.fFieldMapping' },
      { code: 'Add Field', hintKey: 'emailMonitorGuide.fAddField' },
      { code: 'Target Field', hintKey: 'emailMonitorGuide.fTargetField' },
      { code: 'Source · Email attributes', hintKey: 'emailMonitorGuide.fSourceGroupAttributes' },
      { code: 'Subject', hintKey: 'emailMonitorGuide.fSourceSubject' },
      { code: 'From (sender)', hintKey: 'emailMonitorGuide.fSourceFrom' },
      { code: 'To (recipients)', hintKey: 'emailMonitorGuide.fSourceTo' },
      { code: 'Cc', hintKey: 'emailMonitorGuide.fSourceCc' },
      { code: 'Reply-To', hintKey: 'emailMonitorGuide.fSourceReplyTo' },
      { code: 'Sent date', hintKey: 'emailMonitorGuide.fSourceDate' },
      { code: 'Message-ID', hintKey: 'emailMonitorGuide.fSourceMessageId' },
      { code: 'Source · Body / constant', hintKey: 'emailMonitorGuide.fSourceGroupBody' },
      { code: 'Text + HTML (recommended)', hintKey: 'emailMonitorGuide.fSourceTextAndHtml' },
      { code: 'Method · Direct (whole value)', hintKey: 'emailMonitorGuide.fMethodDirect' },
      { code: 'Method · LABEL / BETWEEN / REGEX', hintKey: 'emailMonitorGuide.fMethodParse' },
      { code: 'Rule', hintKey: 'emailMonitorGuide.fRule' },
      { code: 'Required', hintKey: 'emailMonitorGuide.fRequired' },
      { code: 'Preview', hintKey: 'emailMonitorGuide.fPreview' },
      { code: 'sender_email', hintKey: 'emailMonitorGuide.extractAttributeSample' },
    ],
  },
  {
    anchor: 'extract-attachments',
    titleKey: 'emailMonitorGuide.extractAttachmentsTitle',
    bodyKey: 'emailMonitorGuide.extractAttachmentsBody',
    bodyKeys: ['emailMonitorGuide.extractAttachmentsCatalogLead'],
    ...catalog,
    samples: [
      { code: 'Source · Attachments', hintKey: 'emailMonitorGuide.fSourceAttachments' },
      { code: 'Target Field (FILE only)', hintKey: 'emailMonitorGuide.fAttachmentsTarget' },
      { code: 'Method · Direct (whole value)', hintKey: 'emailMonitorGuide.fAttachmentsMethod' },
      { code: 'Required', hintKey: 'emailMonitorGuide.fAttachmentsRequired' },
      { code: 'Attachment filenames (preview)', hintKey: 'emailMonitorGuide.fSampleAttachments' },
    ],
  },
  {
    anchor: 'extract-subtable',
    titleKey: 'emailMonitorGuide.extractSubTableTitle',
    bodyKey: 'emailMonitorGuide.extractSubTableBody',
    bodyKeys: ['emailMonitorGuide.extractSubTableCatalogLead'],
    ...catalog,
    samples: [
      { code: 'Sub-table (HTML table)', hintKey: 'emailMonitorGuide.fSubTableTab' },
      { code: 'Add Sub-table', hintKey: 'emailMonitorGuide.fAddSubTable' },
      { code: 'Sub Table Binding', hintKey: 'emailMonitorGuide.fSubBinding' },
      { code: 'Table # in email', hintKey: 'emailMonitorGuide.fTableIndex' },
      { code: 'Table selector (optional)', hintKey: 'emailMonitorGuide.fTableSelector' },
      { code: 'First row is header', hintKey: 'emailMonitorGuide.fHeaderRow' },
      { code: 'Add Column', hintKey: 'emailMonitorGuide.fAddColumn' },
      { code: 'Column #', hintKey: 'emailMonitorGuide.fColumnIndex' },
    ],
  },
  {
    anchor: 'start-event',
    titleKey: 'emailMonitorGuide.startEventTitle',
    bodyKey: 'emailMonitorGuide.startEventBody',
    bodyKeys: ['emailMonitorGuide.startEventCatalogLead'],
    figure: { src: 'guides/dw-start-event.png', captionKey: 'emailMonitorGuide.startEventFigure' },
    ...catalog,
    samples: [
      { code: 'Inbound Email Trigger', hintKey: 'emailMonitorGuide.fPanelTitle' },
      { code: 'Start process when email arrives', hintKey: 'emailMonitorGuide.fEnable' },
      { code: 'Process Key (auto)', hintKey: 'emailMonitorGuide.fBoundProcess' },
      { code: 'Start Event ID (auto)', hintKey: 'emailMonitorGuide.fBoundEvent' },
      { code: 'Email Monitor', hintKey: 'emailMonitorGuide.fSelectTemplate' },
      { code: 'Inbound Mailbox', hintKey: 'emailMonitorGuide.fStartMailbox' },
      { code: 'Field extraction', hintKey: 'emailMonitorGuide.fExtractionStatus' },
      { code: 'From Filter', hintKey: 'emailMonitorGuide.fFromFilter' },
      { code: 'Subject Filter', hintKey: 'emailMonitorGuide.fSubjectFilter' },
      { code: 'Quote', hintKey: 'emailMonitorGuide.subjectFilterSample' },
      { code: 'Save Start Event binding', hintKey: 'emailMonitorGuide.fSaveBinding' },
    ],
  },
  {
    anchor: 'deploy',
    titleKey: 'emailMonitorGuide.deployTitle',
    bodyKey: 'emailMonitorGuide.deployBody',
    failKeys: [
      'emailMonitorGuide.failUnbound',
      'emailMonitorGuide.failDisabledConn',
      'emailMonitorGuide.failNoId',
      'emailMonitorGuide.failNoProcessKey',
      'emailMonitorGuide.failNoExtraction',
    ],
  },
  {
    titleKey: 'emailMonitorGuide.deleteTitle',
    bodyKey: 'emailMonitorGuide.deleteBody',
  },
]
</script>
