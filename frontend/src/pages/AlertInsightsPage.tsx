import React, { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Input,
  Button,
  Select,
  Typography,
  Space,
  Tag,
  List,
  Progress,
  Row,
  Col,
  Spin,
  Table,
  Drawer,
  Badge,
  Tabs,
  message,
} from 'antd';
import {
  AlertOutlined,
  ThunderboltOutlined,
  CheckCircleOutlined,
  ReloadOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import type { ColumnsType } from 'antd/es/table';
import {
  analyzeAlert,
  getAlertHistory,
  updateAlertStatus,
  AlertAnalyzeRequest,
  AlertAnalyzeResponse,
} from '../services/api';

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

const SEVERITY_COLOR: Record<string, string> = {
  CRITICAL: '#f5222d',
  HIGH: '#fa8c16',
  MEDIUM: '#faad14',
  LOW: '#52c41a',
};

const STATUS_BADGE: Record<string, 'error' | 'warning' | 'success' | 'default'> = {
  NEW: 'error',
  ACKNOWLEDGED: 'warning',
  RESOLVED: 'success',
};

const AlertInsightsPage: React.FC = () => {
  // --- Manual analyze form ---
  const [form, setForm] = useState<AlertAnalyzeRequest>({
    errorMessage: '',
    applicationName: '',
    severity: 'HIGH',
    source: 'appd',
    stackTrace: '',
  });
  const [analyzing, setAnalyzing] = useState(false);

  // --- Alert history from DynamoDB ---
  const [alerts, setAlerts] = useState<AlertAnalyzeResponse[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [filterSource, setFilterSource] = useState<string | undefined>();
  const [filterSeverity, setFilterSeverity] = useState<string | undefined>();

  // --- Detail drawer ---
  const [selectedAlert, setSelectedAlert] = useState<AlertAnalyzeResponse | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const fetchHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const data = await getAlertHistory(filterSource, filterSeverity, 50);
      setAlerts(data);
    } catch {
      message.error('Failed to load alert history');
    } finally {
      setHistoryLoading(false);
    }
  }, [filterSource, filterSeverity]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  // Auto-refresh every 30s
  useEffect(() => {
    const interval = setInterval(fetchHistory, 30000);
    return () => clearInterval(interval);
  }, [fetchHistory]);

  const handleAnalyze = async () => {
    if (!form.errorMessage.trim()) return;
    setAnalyzing(true);
    try {
      const response = await analyzeAlert(form);
      message.success('Alert analyzed and saved');
      // Add to top of list
      setAlerts((prev) => [response, ...prev]);
      // Open detail
      setSelectedAlert(response);
      setDrawerOpen(true);
    } catch {
      message.error('Analysis failed');
    } finally {
      setAnalyzing(false);
    }
  };

  const handleStatusUpdate = async (
    record: AlertAnalyzeResponse,
    newStatus: string,
  ) => {
    try {
      await updateAlertStatus(record.source, record.alertId, newStatus);
      message.success(`Status updated to ${newStatus}`);
      setAlerts((prev) =>
        prev.map((a) =>
          a.alertId === record.alertId ? { ...a, status: newStatus } : a,
        ),
      );
      if (selectedAlert?.alertId === record.alertId) {
        setSelectedAlert({ ...selectedAlert, status: newStatus });
      }
    } catch {
      message.error('Failed to update status');
    }
  };

  const columns: ColumnsType<AlertAnalyzeResponse> = [
    {
      title: 'Status',
      dataIndex: 'status',
      width: 110,
      render: (status: string) => (
        <Badge
          status={STATUS_BADGE[status] || 'default'}
          text={status}
        />
      ),
      filters: [
        { text: 'NEW', value: 'NEW' },
        { text: 'ACKNOWLEDGED', value: 'ACKNOWLEDGED' },
        { text: 'RESOLVED', value: 'RESOLVED' },
      ],
      onFilter: (value, record) => record.status === value,
    },
    {
      title: 'Severity',
      dataIndex: 'severity',
      width: 100,
      render: (sev: string) => (
        <Tag color={SEVERITY_COLOR[sev] || 'default'}>{sev}</Tag>
      ),
    },
    {
      title: 'Source',
      dataIndex: 'source',
      width: 90,
      render: (src: string) => <Tag>{src}</Tag>,
    },
    {
      title: 'Application',
      dataIndex: 'applicationName',
      width: 140,
      ellipsis: true,
    },
    {
      title: 'Error',
      dataIndex: 'errorMessage',
      ellipsis: true,
      render: (msg: string) => (
        <Text style={{ fontSize: 12 }}>{msg}</Text>
      ),
    },
    {
      title: 'Confidence',
      dataIndex: 'confidence',
      width: 100,
      render: (c: number) => (
        <Progress percent={Math.round(c * 100)} size="small" />
      ),
    },
    {
      title: 'Time',
      dataIndex: 'createdAt',
      width: 170,
      render: (t: string) => t ? new Date(t).toLocaleString() : '-',
      sorter: (a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''),
      defaultSortOrder: 'descend',
    },
    {
      title: 'Action',
      width: 160,
      render: (_, record) => (
        <Space size="small">
          <Button
            size="small"
            icon={<EyeOutlined />}
            onClick={() => {
              setSelectedAlert(record);
              setDrawerOpen(true);
            }}
          >
            Detail
          </Button>
          {record.status === 'NEW' && (
            <Button
              size="small"
              type="link"
              onClick={() => handleStatusUpdate(record, 'ACKNOWLEDGED')}
            >
              ACK
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto' }}>
      <Title level={3}>
        <AlertOutlined /> Alert Insights
      </Title>

      <Tabs
        items={[
          {
            key: 'history',
            label: `Alert History (${alerts.length})`,
            children: (
              <>
                {/* Filters */}
                <Card size="small" style={{ marginBottom: 12 }}>
                  <Space wrap>
                    <Select
                      placeholder="Filter by source"
                      allowClear
                      value={filterSource}
                      onChange={setFilterSource}
                      style={{ width: 160 }}
                      options={[
                        { label: 'AppDynamics', value: 'appd' },
                        { label: 'Splunk', value: 'splunk' },
                        { label: 'Datadog', value: 'datadog' },
                      ]}
                    />
                    <Select
                      placeholder="Filter by severity"
                      allowClear
                      value={filterSeverity}
                      onChange={setFilterSeverity}
                      style={{ width: 160 }}
                      options={['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(
                        (s) => ({ label: s, value: s }),
                      )}
                    />
                    <Button
                      icon={<ReloadOutlined />}
                      onClick={fetchHistory}
                      loading={historyLoading}
                    >
                      Refresh
                    </Button>
                  </Space>
                </Card>

                {/* Alert Table */}
                <Table
                  dataSource={alerts}
                  columns={columns}
                  rowKey="alertId"
                  loading={historyLoading}
                  size="small"
                  pagination={{ pageSize: 20, showSizeChanger: true }}
                  rowClassName={(record) =>
                    record.severity === 'CRITICAL' ? 'critical-row' : ''
                  }
                />
              </>
            ),
          },
          {
            key: 'manual',
            label: 'Manual Analysis',
            children: (
              <Card>
                <Row gutter={16}>
                  <Col span={12}>
                    <Text strong>Error Message *</Text>
                    <TextArea
                      rows={4}
                      value={form.errorMessage}
                      onChange={(e) =>
                        setForm({ ...form, errorMessage: e.target.value })
                      }
                      placeholder="java.lang.OutOfMemoryError: Java heap space..."
                      style={{ marginTop: 4 }}
                    />
                  </Col>
                  <Col span={12}>
                    <Text strong>Stack Trace (optional)</Text>
                    <TextArea
                      rows={4}
                      value={form.stackTrace}
                      onChange={(e) =>
                        setForm({ ...form, stackTrace: e.target.value })
                      }
                      placeholder="Paste stack trace here..."
                      style={{ marginTop: 4 }}
                    />
                  </Col>
                </Row>
                <Row gutter={16} style={{ marginTop: 12 }}>
                  <Col span={8}>
                    <Text strong>Application</Text>
                    <Input
                      value={form.applicationName}
                      onChange={(e) =>
                        setForm({ ...form, applicationName: e.target.value })
                      }
                      placeholder="payment-service"
                      style={{ marginTop: 4 }}
                    />
                  </Col>
                  <Col span={8}>
                    <Text strong>Severity</Text>
                    <Select
                      value={form.severity}
                      onChange={(v) => setForm({ ...form, severity: v })}
                      style={{ width: '100%', marginTop: 4 }}
                      options={['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(
                        (s) => ({ label: s, value: s }),
                      )}
                    />
                  </Col>
                  <Col span={8}>
                    <Text strong>Source</Text>
                    <Select
                      value={form.source}
                      onChange={(v) => setForm({ ...form, source: v })}
                      style={{ width: '100%', marginTop: 4 }}
                      options={[
                        { label: 'AppDynamics', value: 'appd' },
                        { label: 'Splunk', value: 'splunk' },
                        { label: 'Datadog', value: 'datadog' },
                        { label: 'Other', value: 'other' },
                      ]}
                    />
                  </Col>
                </Row>
                <Button
                  type="primary"
                  icon={<ThunderboltOutlined />}
                  onClick={handleAnalyze}
                  loading={analyzing}
                  style={{ marginTop: 16 }}
                  size="large"
                >
                  Analyze Alert
                </Button>
              </Card>
            ),
          },
        ]}
      />

      {/* Detail Drawer */}
      <Drawer
        title={
          <Space>
            <AlertOutlined />
            Alert Detail
            {selectedAlert && (
              <>
                <Tag color={SEVERITY_COLOR[selectedAlert.severity]}>
                  {selectedAlert.severity}
                </Tag>
                <Badge
                  status={STATUS_BADGE[selectedAlert.status] || 'default'}
                  text={selectedAlert.status}
                />
              </>
            )}
          </Space>
        }
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={720}
        extra={
          selectedAlert &&
          selectedAlert.status !== 'RESOLVED' && (
            <Space>
              {selectedAlert.status === 'NEW' && (
                <Button
                  onClick={() =>
                    handleStatusUpdate(selectedAlert, 'ACKNOWLEDGED')
                  }
                >
                  Acknowledge
                </Button>
              )}
              <Button
                type="primary"
                onClick={() =>
                  handleStatusUpdate(selectedAlert, 'RESOLVED')
                }
              >
                Resolve
              </Button>
            </Space>
          )
        }
      >
        {selectedAlert && (
          <div>
            {/* Meta */}
            <Card size="small" style={{ marginBottom: 12 }}>
              <Row gutter={16}>
                <Col span={8}>
                  <Text type="secondary">Source</Text>
                  <div><Tag>{selectedAlert.source}</Tag></div>
                </Col>
                <Col span={8}>
                  <Text type="secondary">Application</Text>
                  <div><Text strong>{selectedAlert.applicationName}</Text></div>
                </Col>
                <Col span={8}>
                  <Text type="secondary">Time</Text>
                  <div>
                    <Text>
                      {selectedAlert.createdAt
                        ? new Date(selectedAlert.createdAt).toLocaleString()
                        : '-'}
                    </Text>
                  </div>
                </Col>
              </Row>
            </Card>

            {/* Error */}
            <Card
              size="small"
              title="Error Message"
              style={{ marginBottom: 12 }}
            >
              <Paragraph
                code
                style={{
                  whiteSpace: 'pre-wrap',
                  fontSize: 12,
                  maxHeight: 120,
                  overflow: 'auto',
                }}
              >
                {selectedAlert.errorMessage}
              </Paragraph>
            </Card>

            {/* RCA */}
            <Card
              title="Root Cause Analysis"
              extra={
                <Tag color="blue">
                  Confidence: {(selectedAlert.confidence * 100).toFixed(0)}%
                </Tag>
              }
              style={{ marginBottom: 12 }}
            >
              <ReactMarkdown>{selectedAlert.rootCauseAnalysis}</ReactMarkdown>
            </Card>

            {/* Recommended Actions */}
            <Card title="Recommended Actions" style={{ marginBottom: 12 }}>
              <List
                dataSource={selectedAlert.recommendedActions}
                renderItem={(action, i) => (
                  <List.Item>
                    <Space>
                      <CheckCircleOutlined style={{ color: '#52c41a' }} />
                      <Text>
                        {i + 1}. {action}
                      </Text>
                    </Space>
                  </List.Item>
                )}
              />
            </Card>

            {/* Similar Incidents */}
            {selectedAlert.similarIncidents.length > 0 && (
              <Card title="Similar Past Incidents" style={{ marginBottom: 12 }}>
                {selectedAlert.similarIncidents.map((inc, i) => (
                  <div
                    key={i}
                    style={{
                      marginBottom: 12,
                      padding: 8,
                      background: '#fafafa',
                      borderRadius: 6,
                    }}
                  >
                    <Text strong>{inc.errorType || inc.incidentId}</Text>
                    <Progress
                      percent={Math.round(inc.similarity * 100)}
                      size="small"
                    />
                    <Paragraph
                      type="secondary"
                      ellipsis={{ rows: 2 }}
                      style={{ margin: 0 }}
                    >
                      {inc.resolution}
                    </Paragraph>
                  </div>
                ))}
              </Card>
            )}

            {/* Related Knowledge */}
            {selectedAlert.relatedKnowledge.length > 0 && (
              <Card title="Related Knowledge">
                {selectedAlert.relatedKnowledge.map((k, i) => (
                  <div key={i} style={{ marginBottom: 8 }}>
                    <a href={k.url} target="_blank" rel="noreferrer">
                      {k.title}
                    </a>
                    <Tag style={{ marginLeft: 8 }}>{k.collection}</Tag>
                    <Progress
                      percent={Math.round(k.score * 100)}
                      size="small"
                      style={{ width: 80, display: 'inline-block', marginLeft: 8 }}
                    />
                  </div>
                ))}
              </Card>
            )}
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default AlertInsightsPage;
