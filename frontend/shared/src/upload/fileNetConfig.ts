export interface FileNetHeaderItem {
  name: string
  value: string
}

export interface FileNetDocProperty {
  propertyName: string
  dataType: 'String' | 'DateTime'
  source: 'static' | 'formField'
  value?: string
  fieldKey?: string
}

export interface FileNetOrderByItem {
  field: string
  direction: 'ASC' | 'DESC'
}

export interface FileNetConfig {
  enabled: boolean
  connectionUid: string
  headerInfo: FileNetHeaderItem[]
  repositoryDetail: {
    repositoryID: string
    documentClass: string
    objectStoreName: string
  }
  docProperty: FileNetDocProperty[]
  searchDetailList: unknown[]
  retrieveRequest: Record<string, unknown>
  orderBy: FileNetOrderByItem[]
}

export const DEFAULT_FILE_NET_CONFIG: FileNetConfig = {
  enabled: false,
  connectionUid: '',
  headerInfo: [],
  repositoryDetail: {
    repositoryID: '',
    documentClass: '',
    objectStoreName: '',
  },
  docProperty: [],
  searchDetailList: [],
  retrieveRequest: {},
  orderBy: [],
}

export function normalizeFileNetConfig(raw: unknown): FileNetConfig {
  if (!raw || typeof raw !== 'object') {
    return { ...DEFAULT_FILE_NET_CONFIG, repositoryDetail: { ...DEFAULT_FILE_NET_CONFIG.repositoryDetail } }
  }
  const o = raw as Record<string, unknown>
  const repo = (o.repositoryDetail && typeof o.repositoryDetail === 'object')
    ? o.repositoryDetail as Record<string, unknown>
    : {}
  return {
    enabled: o.enabled === true,
    connectionUid: typeof o.connectionUid === 'string' ? o.connectionUid : '',
    headerInfo: Array.isArray(o.headerInfo) ? o.headerInfo as FileNetHeaderItem[] : [],
    repositoryDetail: {
      repositoryID: typeof repo.repositoryID === 'string' ? repo.repositoryID : '',
      documentClass: typeof repo.documentClass === 'string' ? repo.documentClass : '',
      objectStoreName: typeof repo.objectStoreName === 'string' ? repo.objectStoreName : '',
    },
    docProperty: Array.isArray(o.docProperty) ? o.docProperty as FileNetDocProperty[] : [],
    searchDetailList: Array.isArray(o.searchDetailList) ? o.searchDetailList : [],
    retrieveRequest: (o.retrieveRequest && typeof o.retrieveRequest === 'object')
      ? o.retrieveRequest as Record<string, unknown>
      : {},
    orderBy: Array.isArray(o.orderBy) ? o.orderBy as FileNetOrderByItem[] : [],
  }
}
