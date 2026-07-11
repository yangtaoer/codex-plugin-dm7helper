export type RuntimeSummary = {
  sessionShortId: string
  currentVersion: string
  runningCount: number
  connections: number
}

export type SafeConnection = {
  id: string
  name: string
  driverFileName?: string
  driverSha256?: string
  configured: boolean
  connected: boolean
  hasPassword: boolean
  driverClass?: string
  jdbcUrl?: string
  urlSummary: string
  username?: string
  schema?: string | null
  connectTimeoutSeconds?: number
  socketTimeoutSeconds?: number
  queryTimeoutSeconds?: number
  maxRows?: number
  maxBytes?: number
  isDefault: boolean
}

export type ConnectionList = { connections: SafeConnection[] }
export const EXECUTION_STATUSES = ['QUEUED', 'CONNECTING', 'PARSING', 'EXECUTING', 'COMMITTING', 'LOGGING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'] as const
export type ExecutionStatus = typeof EXECUTION_STATUSES[number]
export const EXECUTION_EVENT_NAMES = EXECUTION_STATUSES.map((status) => status.toLowerCase()) as Lowercase<ExecutionStatus>[]
export type ExecutionSource = 'MCP' | 'CONSOLE'
export type SqlPurpose = 'PRODUCTION_CHANGE' | 'MIGRATION' | 'TEST' | 'MOCK' | 'SEED' | 'SAMPLE'
export type SqlKind = 'QUERY' | 'EXPLAIN' | 'DDL' | 'DML' | 'DCL' | 'TRANSACTION' | 'SESSION' | 'CALL' | 'ANONYMOUS_BLOCK' | 'UNKNOWN'
export type HistoryQuery = {
  status?: ExecutionStatus
  source?: ExecutionSource
  purpose?: SqlPurpose
  offset?: number
  limit?: number
  startedAfter?: string
  startedBefore?: string
  recorded?: boolean
  correlationId?: string
  success?: boolean
  kind?: SqlKind
}
export type HistoryItem = {
  executionId: string; correlationId: string; connectionFingerprint: string
  source: ExecutionSource; purpose: SqlPurpose | null; status: ExecutionStatus; startedAt: string
  completedAt: string | null; affectedRows: number; returnedRows: number
  recorded: boolean; exclusionReason: string | null
  sqlSummary?: string
}
export type HistoryPage = { items: HistoryItem[]; offset: number; limit: number; hasMore: boolean }
export type EventRecord = { id: string; executionId: string; status: Lowercase<ExecutionStatus>; timestamp: string; detail: string }
export type DownloadArtifact = { filename: string; blob: Blob }
export type DeleteConnectionResult = { deleted: true; defaultConnectionId: string | null }
export type DeleteConnectionInput = { replacementDefaultId?: string; leaveWithoutDefault?: true }
export type ConnectionInput = {
  name: string; driverJar: string; driverClass?: string; jdbcUrl: string; username: string
  password?: string; clearPassword?: boolean; schema?: string | null; connectTimeoutSeconds?: number; socketTimeoutSeconds?: number
  queryTimeoutSeconds?: number; maxRows?: number; maxBytes?: number; isDefault?: boolean
}
export type ConnectionTestResult = { success: boolean; latencyMs: number; driverVersion: string; serverVersion: string; actualUser: string; actualSchema: string; chineseRoundTrip: boolean; restartRequired: boolean; warnings: string[] }
export type UrlDiagnostics = { urlSummary: string; warnings: string[] }
export type SqlParameter = { jdbcType: number; value: unknown }
export type QueryInput = { connectionId?: string; executionId?: string; sql: string; parameters?: SqlParameter[]; maxRows?: number; maxBytes?: number; timeoutSeconds?: number }
export type SqlClassification = { statementCount: number; kinds: SqlKind[]; queryOnly: boolean; requiresPurpose: boolean; atomicAllowed: boolean }
export type SafeExecutionError = { correlationId: string; phase: string; message: string; sqlState: string | null; errorCode: number | null; restartRequired: boolean }
export type QueryColumn = { outputLabel: string; originalLabel: string; originalName: string; jdbcType: number; typeName: string }
export type QueryResult = { executionId: string; success: boolean; columns: QueryColumn[]; rows: Record<string, unknown>[]; truncated: boolean; returnedRows: number; bytes: number; elapsedMillis: number; databaseFingerprint: string; error: SafeExecutionError | null }
export type ExecuteInput = { connectionId?: string; executionId?: string; sql: string; parameters?: SqlParameter[]; purpose: SqlPurpose; atomic?: boolean; continueOnError?: boolean; timeoutSeconds?: number }
export type StatementResult = { index: number; kind: SqlKind; success: boolean; committed: boolean; rowCount: number; recorded: boolean; exclusionReason: string | null; commitBehavior: string; elapsedMillis: number; error: SafeExecutionError | null }
export type ExecuteResult = { executionId: string; success: boolean; status: ExecutionStatus; statements: StatementResult[]; elapsedMillis: number; databaseFingerprint: string; error: SafeExecutionError | null }
export type MetadataQuery = { connectionId?: string; schemaPattern?: string; objectPattern?: string; offset?: number; limit?: number }
export type SchemaColumn = { name: string; jdbcType: number; typeName: string; nullable: boolean; ordinal: number }
export type SchemaObject = { schema: string; name: string; type: string; columns: SchemaColumn[] }
export type SchemaPage = { items: SchemaObject[]; offset: number; limit: number; hasMore: boolean }
export type ExecutionDetailError = Omit<SafeExecutionError, 'correlationId'> & { correlationId?: string }
export type ExecutionDetailSummary = HistoryItem & {
  phase: string | null
  sqlSummary: string
  error: ExecutionDetailError | null
}
export type ExecutionDetailStatement = {
  index: number
  kind: SqlKind
  status: string
  phase: string | null
  rowCount: number
  success: boolean
  committed: boolean
  commitBehavior: string | null
  elapsedMillis: number
  recorded: boolean
  exclusionReason: string | null
  sqlSummary: string
  error: ExecutionDetailError | null
}
export type ExecutionDetail = { summary: ExecutionDetailSummary; statements: ExecutionDetailStatement[]; events: { sequence: number; status: string; timestamp: string; detail: string }[] }
export type CancelResult = { executionId: string; cancelRequested: boolean }
export type ReleaseEntry={sequence:number|null;index:number;kind:SqlKind;status:string;source:ExecutionSource|null;purpose:SqlPurpose|null;recorded:boolean;exclusionReason:string|null;createdAt:string;sqlSummary:string}
export type ReleaseArtifact={id:string;state:'SEALED'|'RECOVERY_REQUIRED'|'COMPLETE';version:string;filename:string|null;sha256:string|null;expectedSha256?:string|null;byteLength:number|null;statementCount:number;firstSequence:number|null;lastSequence:number|null;createdAt:string;completedAt:string|null;downloadAvailable:boolean;downloadUrl:string|null;integrityState:string}
export type ReleaseSnapshot = { sessionShortId:string;currentVersion: string; databaseFingerprint: string | null;bindingState:'UNBOUND'|'MATCH'|'MISMATCH'|'NO_DEFAULT'; statementCount: number; excludedCount: number; failedCount: number; sqlPreview: string;previewTruncated:boolean; firstSequence: number | null; lastSequence: number | null;runningCount:number;entriesTruncated:boolean;entries:ReleaseEntry[];artifacts:ReleaseArtifact[] }
export type ExportArtifact = { id: string; version: string; newActiveVersion: string; filename: string; byteLength: number; sha256: string; sealedSourceSha256: string; statementCount: number; firstSequence: number | null; lastSequence: number | null; createdAt: string; downloadUrl: string }

export interface ApiClient {
  runtime(signal?: AbortSignal): Promise<RuntimeSummary>
  history(query?: HistoryQuery, signal?: AbortSignal): Promise<HistoryPage>
  removeConnection(id: string, input?: DeleteConnectionInput, signal?: AbortSignal): Promise<DeleteConnectionResult>
  downloadArtifact(id: string, signal?: AbortSignal): Promise<DownloadArtifact>
  listConnections(signal?: AbortSignal): Promise<ConnectionList>
  getConnection(id: string, signal?: AbortSignal): Promise<SafeConnection>
  createConnection(input: ConnectionInput, signal?: AbortSignal): Promise<SafeConnection>
  updateConnection(id: string, input: Partial<ConnectionInput>, signal?: AbortSignal): Promise<SafeConnection>
  setDefaultConnection(id: string, signal?: AbortSignal): Promise<SafeConnection>
  testConnection(id: string, signal?: AbortSignal): Promise<ConnectionTestResult>
  diagnoseUrl(jdbcUrl: string, signal?: AbortSignal): Promise<UrlDiagnostics>
  classifySql(sql: string, signal?: AbortSignal): Promise<SqlClassification>
  query(input: QueryInput, signal?: AbortSignal): Promise<QueryResult>
  execute(input: ExecuteInput, signal?: AbortSignal): Promise<ExecuteResult>
  metadata(query?: MetadataQuery, signal?: AbortSignal): Promise<SchemaPage>
  getExecution(id: string, signal?: AbortSignal): Promise<ExecutionDetail>
  cancelExecution(id: string, signal?: AbortSignal): Promise<CancelResult>
  release(signal?: AbortSignal): Promise<ReleaseSnapshot>
  releaseExport(confirm: true, signal?: AbortSignal): Promise<ExportArtifact>
  releaseRecover(version:string,confirm:true,signal?:AbortSignal):Promise<ExportArtifact>
}
