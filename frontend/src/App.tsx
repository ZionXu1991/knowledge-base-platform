import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, Layout, Menu, theme } from 'antd';
import {
  MessageOutlined,
  SearchOutlined,
  AlertOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import ChatPage from './pages/ChatPage';
import SearchPage from './pages/SearchPage';
import AlertInsightsPage from './pages/AlertInsightsPage';
import AdminPage from './pages/AdminPage';

const { Sider, Content } = Layout;

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  const menuItems = [
    { key: '/chat', icon: <MessageOutlined />, label: 'Knowledge Q&A' },
    { key: '/search', icon: <SearchOutlined />, label: 'Semantic Search' },
    { key: '/alerts', icon: <AlertOutlined />, label: 'Alert Insights' },
    { key: '/admin', icon: <SettingOutlined />, label: 'Admin' },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={220}>
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontSize: 18,
            fontWeight: 600,
          }}
        >
          KB Platform
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Content style={{ padding: 24, background: '#f5f5f5' }}>
        <Routes>
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/alerts" element={<AlertInsightsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Routes>
      </Content>
    </Layout>
  );
};

const App: React.FC = () => (
  <ConfigProvider theme={{ algorithm: theme.defaultAlgorithm }}>
    <BrowserRouter>
      <AppLayout />
    </BrowserRouter>
  </ConfigProvider>
);

export default App;
