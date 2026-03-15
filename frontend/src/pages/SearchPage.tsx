import React, { useState } from 'react';
import {
  Card,
  Input,
  List,
  Tag,
  Typography,
  Space,
  Progress,
  Select,
  Slider,
  Row,
  Col,
} from 'antd';
import { SearchOutlined, LinkOutlined } from '@ant-design/icons';
import { semanticSearch, Source } from '../services/api';

const { Title, Text, Paragraph } = Typography;

const COLLECTIONS = [
  { label: 'Confluence Docs', value: 'confluence_docs' },
  { label: 'Incident Resolutions', value: 'incident_resolutions' },
  { label: 'Runbooks', value: 'runbooks' },
];

const SearchPage: React.FC = () => {
  const [results, setResults] = useState<Source[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedCollections, setSelectedCollections] = useState<string[]>([]);
  const [threshold, setThreshold] = useState(0.7);
  const [searchTime, setSearchTime] = useState<number | null>(null);

  const handleSearch = async (value: string) => {
    if (!value.trim()) return;
    setLoading(true);
    const start = Date.now();
    try {
      const data = await semanticSearch(value, 15, threshold);
      setResults(
        selectedCollections.length > 0
          ? data.filter((d) => selectedCollections.includes(d.collection))
          : data,
      );
      setSearchTime(Date.now() - start);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const collectionColor: Record<string, string> = {
    confluence_docs: 'blue',
    incident_resolutions: 'orange',
    runbooks: 'green',
  };

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <Title level={3}>Semantic Search</Title>
      <Text type="secondary">
        Find relevant documents by meaning, not just keywords.
      </Text>

      <Card style={{ marginTop: 16 }}>
        <Input.Search
          size="large"
          placeholder="Search the knowledge base..."
          enterButton={<><SearchOutlined /> Search</>}
          onSearch={handleSearch}
          loading={loading}
        />
        <Row gutter={16} style={{ marginTop: 12 }}>
          <Col span={16}>
            <Text type="secondary">Filter collections:</Text>
            <Select
              mode="multiple"
              placeholder="All collections"
              options={COLLECTIONS}
              value={selectedCollections}
              onChange={setSelectedCollections}
              style={{ width: '100%', marginTop: 4 }}
              allowClear
            />
          </Col>
          <Col span={8}>
            <Text type="secondary">
              Min similarity: {(threshold * 100).toFixed(0)}%
            </Text>
            <Slider
              min={0.5}
              max={0.95}
              step={0.05}
              value={threshold}
              onChange={setThreshold}
            />
          </Col>
        </Row>
      </Card>

      {searchTime !== null && (
        <Text type="secondary" style={{ display: 'block', margin: '12px 0' }}>
          {results.length} results in {searchTime}ms
        </Text>
      )}

      <List
        dataSource={results}
        loading={loading}
        renderItem={(item) => (
          <Card size="small" style={{ marginBottom: 8 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Space>
                <LinkOutlined />
                {item.url ? (
                  <a href={item.url} target="_blank" rel="noreferrer">
                    <Text strong>{item.title}</Text>
                  </a>
                ) : (
                  <Text strong>{item.title}</Text>
                )}
                <Tag color={collectionColor[item.collection] || 'default'}>
                  {item.collection}
                </Tag>
                <Progress
                  percent={Math.round(item.score * 100)}
                  size="small"
                  style={{ width: 100 }}
                  strokeColor={item.score > 0.85 ? '#52c41a' : undefined}
                />
              </Space>
              <Paragraph type="secondary" ellipsis={{ rows: 3 }}>
                {item.snippet}
              </Paragraph>
            </Space>
          </Card>
        )}
      />
    </div>
  );
};

export default SearchPage;
