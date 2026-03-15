import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1/kb',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
});

// --- Types ---

export interface Source {
  title: string;
  url: string;
  collection: string;
  score: number;
  snippet: string;
}

export interface QueryResponse {
  answer: string;
  confidence: number;
  sources: Source[];
  latencyMs: number;
}

export interface AlertAnalyzeRequest {
  errorMessage: string;
  applicationName?: string;
  severity?: string;
  source?: string;
  stackTrace?: string;
  metadata?: Record<string, string>;
}

export interface SimilarIncident {
  incidentId: string;
  errorType: string;
  resolution: string;
  similarity: number;
}

export interface AlertAnalyzeResponse {
  alertId: string;
  source: string;
  applicationName: string;
  errorMessage: string;
  rootCauseAnalysis: string;
  recommendedActions: string[];
  relatedKnowledge: Source[];
  severity: string;
  confidence: number;
  similarIncidents: SimilarIncident[];
  createdAt: string;
  status: string;  // NEW, ACKNOWLEDGED, RESOLVED
}

export interface IngestStatus {
  status: string;
  totalPages: number;
  processedPages: number;
  totalChunks: number;
  lastSyncTime: string | null;
  error: string | null;
}

// --- API Functions ---

export async function queryKnowledgeBase(
  question: string,
  collections?: string[],
): Promise<QueryResponse> {
  const { data } = await api.post<QueryResponse>('/query', {
    question,
    collections,
  });
  return data;
}

export async function semanticSearch(
  q: string,
  topK = 10,
  threshold = 0.7,
): Promise<Source[]> {
  const { data } = await api.get<Source[]>('/search', {
    params: { q, topK, threshold },
  });
  return data;
}

export async function analyzeAlert(
  request: AlertAnalyzeRequest,
): Promise<AlertAnalyzeResponse> {
  const { data } = await api.post<AlertAnalyzeResponse>(
    '/alert-analyze',
    request,
  );
  return data;
}

export async function triggerConfluenceSync(
  spaceKeys?: string[],
  fullSync = false,
): Promise<IngestStatus> {
  const { data } = await api.post<IngestStatus>('/ingest/confluence', {
    spaceKeys,
    fullSync,
  });
  return data;
}

export async function getSyncStatus(): Promise<IngestStatus> {
  const { data } = await api.get<IngestStatus>('/sync-status');
  return data;
}

export async function getSources(): Promise<Record<string, number>> {
  const { data } = await api.get<Record<string, number>>('/sources');
  return data;
}

// --- Alert History (from DynamoDB) ---

export async function getAlertHistory(
  source?: string,
  severity?: string,
  limit = 50,
): Promise<AlertAnalyzeResponse[]> {
  const { data } = await api.get<AlertAnalyzeResponse[]>('/alerts', {
    params: { source, severity, limit },
  });
  return data;
}

export async function getAlertDetail(
  source: string,
  alertId: string,
): Promise<AlertAnalyzeResponse> {
  const { data } = await api.get<AlertAnalyzeResponse>(
    `/alerts/${source}/${alertId}`,
  );
  return data;
}

export async function updateAlertStatus(
  source: string,
  alertId: string,
  status: string,
): Promise<void> {
  await api.patch(`/alerts/${source}/${alertId}/status`, null, {
    params: { status },
  });
}
