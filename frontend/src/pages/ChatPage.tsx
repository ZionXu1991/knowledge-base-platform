import React, { useState, useRef, useEffect } from 'react';
import {
  Card,
  Input,
  Button,
  Typography,
  Space,
  Tag,
  Spin,
  Collapse,
  Progress,
} from 'antd';
import { SendOutlined, LinkOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { queryKnowledgeBase, QueryResponse } from '../services/api';

const { Title, Text, Paragraph } = Typography;

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  response?: QueryResponse;
  timestamp: Date;
}

const ChatPage: React.FC = () => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = async () => {
    const question = input.trim();
    if (!question || loading) return;

    setInput('');
    setMessages((prev) => [
      ...prev,
      { role: 'user', content: question, timestamp: new Date() },
    ]);
    setLoading(true);

    try {
      const response = await queryKnowledgeBase(question);
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: response.answer,
          response,
          timestamp: new Date(),
        },
      ]);
    } catch (err: any) {
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: `Error: ${err.message || 'Failed to get answer'}`,
          timestamp: new Date(),
        },
      ]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <Title level={3}>Knowledge Q&A</Title>
      <Text type="secondary">
        Ask questions about your infrastructure, runbooks, and past incidents.
      </Text>

      <div
        style={{
          marginTop: 16,
          height: 'calc(100vh - 280px)',
          overflowY: 'auto',
          padding: '0 8px',
        }}
      >
        {messages.map((msg, i) => (
          <div
            key={i}
            style={{
              display: 'flex',
              justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
              marginBottom: 16,
            }}
          >
            <Card
              size="small"
              style={{
                maxWidth: '80%',
                background: msg.role === 'user' ? '#1677ff' : '#fff',
                color: msg.role === 'user' ? '#fff' : undefined,
                borderRadius: 12,
              }}
              styles={{
                body: {
                  color: msg.role === 'user' ? '#fff' : undefined,
                },
              }}
            >
              {msg.role === 'assistant' ? (
                <>
                  <ReactMarkdown>{msg.content}</ReactMarkdown>
                  {msg.response && (
                    <>
                      <div style={{ marginTop: 12 }}>
                        <Space>
                          <Tag color="blue">
                            Confidence:{' '}
                            {(msg.response.confidence * 100).toFixed(0)}%
                          </Tag>
                          <Tag>{msg.response.latencyMs}ms</Tag>
                        </Space>
                      </div>
                      {msg.response.sources.length > 0 && (
                        <Collapse
                          size="small"
                          style={{ marginTop: 8 }}
                          items={[
                            {
                              key: '1',
                              label: `Sources (${msg.response.sources.length})`,
                              children: msg.response.sources.map((s, j) => (
                                <div
                                  key={j}
                                  style={{
                                    marginBottom: 8,
                                    padding: '4px 0',
                                    borderBottom: '1px solid #f0f0f0',
                                  }}
                                >
                                  <Space>
                                    <LinkOutlined />
                                    {s.url ? (
                                      <a
                                        href={s.url}
                                        target="_blank"
                                        rel="noreferrer"
                                      >
                                        {s.title}
                                      </a>
                                    ) : (
                                      <Text strong>{s.title}</Text>
                                    )}
                                    <Tag>{s.collection}</Tag>
                                    <Progress
                                      percent={Math.round(s.score * 100)}
                                      size="small"
                                      style={{ width: 80 }}
                                    />
                                  </Space>
                                  <Paragraph
                                    type="secondary"
                                    ellipsis={{ rows: 2 }}
                                    style={{ margin: '4px 0 0 24px' }}
                                  >
                                    {s.snippet}
                                  </Paragraph>
                                </div>
                              )),
                            },
                          ]}
                        />
                      )}
                    </>
                  )}
                </>
              ) : (
                <div style={{ color: '#fff' }}>{msg.content}</div>
              )}
            </Card>
          </div>
        ))}
        {loading && (
          <div style={{ textAlign: 'center', padding: 20 }}>
            <Spin tip="Searching knowledge base..." />
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Ask a question... (e.g., How to restart the payment service?)"
          autoSize={{ minRows: 1, maxRows: 4 }}
          disabled={loading}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          loading={loading}
          style={{ height: 'auto' }}
        >
          Ask
        </Button>
      </div>
    </div>
  );
};

export default ChatPage;
