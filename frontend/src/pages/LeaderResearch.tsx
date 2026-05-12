import { useEffect, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd'
import {
  ExperimentOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type {
  Account,
  LeaderPaperPosition,
  LeaderPaperTrade,
  LeaderResearchCandidate,
  LeaderResearchCandidateDetail,
  LeaderResearchCandidateListResponse,
  LeaderResearchShortlistCard,
  LeaderResearchShortlistResponse,
  LeaderResearchSourceState,
  LeaderResearchState,
  LeaderResearchSummary,
  LeaderResearchWatchlistPreviewResponse
} from '../types'

const { Paragraph, Text, Title } = Typography

const STATE_COLORS: Record<LeaderResearchState, string> = {
  DISCOVERED: 'default',
  CANDIDATE: 'blue',
  PAPER: 'geekblue',
  TRIAL_READY: 'green',
  COOLDOWN: 'orange',
  RETIRED: 'red'
}

const VALUATION_COLORS: Record<string, string> = {
  AVAILABLE: 'green',
  CONFIRMED_ZERO: 'purple',
  UNKNOWN: 'orange',
  UNAVAILABLE: 'red',
  NO_MATCH: 'volcano'
}

const formatDate = (timestamp?: number) => {
  if (!timestamp) return '-'
  return dayjs(timestamp).format('YYYY-MM-DD HH:mm')
}

const usdc = (value?: string) => value ? `${value} USDC` : '-'

const approvalPreview = (candidate?: LeaderResearchCandidate | null) => ({
  fixedAmount: usdc(candidate?.suggestedFixedAmount),
  maxDailyLoss: usdc(candidate?.suggestedMaxDailyLoss),
  maxDailyOrders: candidate?.suggestedMaxDailyOrders ?? '-',
  priceRange: candidate?.suggestedMinPrice || candidate?.suggestedMaxPrice
    ? `${candidate?.suggestedMinPrice ?? '-'} - ${candidate?.suggestedMaxPrice ?? '-'}`
    : '-',
  maxPositionValue: usdc(candidate?.suggestedMaxPositionValue)
})

const responseError = (code?: number, msg?: string, fallback?: string) => {
  if (!msg) return fallback || 'Request failed'
  if (code && [401, 403, 409, 422, 2001, 2004, 4201, 4264, 4265, 5456].includes(code)) {
    return `${msg} (${code})`
  }
  return msg
}

const valuationTag = (status?: string) => {
  if (!status) return <Tag>-</Tag>
  return <Tag color={VALUATION_COLORS[status] || 'default'}>{status}</Tag>
}

const LeaderResearch: React.FC = () => {
  const { t } = useTranslation()
  const [summary, setSummary] = useState<LeaderResearchSummary | null>(null)
  const [candidates, setCandidates] = useState<LeaderResearchCandidateListResponse>({ list: [], total: 0, summary: summaryFallback })
  const [sourceHealth, setSourceHealth] = useState<LeaderResearchSourceState[]>([])
  const [shortlist, setShortlist] = useState<LeaderResearchShortlistResponse | null>(null)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [activeAccountId, setActiveAccountId] = useState<number | undefined>()
  const [stateFilter, setStateFilter] = useState<LeaderResearchState | undefined>()
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [running, setRunning] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detail, setDetail] = useState<LeaderResearchCandidateDetail | null>(null)
  const [approvalCandidate, setApprovalCandidate] = useState<LeaderResearchCandidate | null>(null)
  const [approvalLoading, setApprovalLoading] = useState(false)
  const [approvalError, setApprovalError] = useState<string | null>(null)
  const [watchlistRaw, setWatchlistRaw] = useState('')
  const [watchlistPreview, setWatchlistPreview] = useState<LeaderResearchWatchlistPreviewResponse | null>(null)
  const [watchlistLoading, setWatchlistLoading] = useState(false)
  const [approvalForm] = Form.useForm()
  const autopilotApprovalEnabled = Form.useWatch('autopilotEnabled', approvalForm)

  const loadAll = async (showLoading = true) => {
    if (showLoading) setLoading(true)
    try {
      const [candidateResp, summaryResp, sourceResp, accountResp, shortlistResp, watchlistResp] = await Promise.all([
        apiService.leaderResearch.listCandidates({ page: 0, size: 50, state: stateFilter, query: query || undefined }),
        apiService.leaderResearch.summary(),
        apiService.leaderResearch.sourceHealth(),
        apiService.accounts.list(),
        apiService.leaderResearch.shortlist({ accountId: activeAccountId, limit: 8 }),
        apiService.leaderResearch.watchlist()
      ])
      if (candidateResp.data.code === 0 && candidateResp.data.data) {
        setCandidates(candidateResp.data.data)
      } else {
        message.error(candidateResp.data.msg || t('leaderResearch.fetchFailed'))
      }
      if (summaryResp.data.code === 0 && summaryResp.data.data) {
        setSummary(summaryResp.data.data)
      }
      if (sourceResp.data.code === 0 && sourceResp.data.data) {
        setSourceHealth(sourceResp.data.data)
      }
      if (accountResp.data.code === 0 && accountResp.data.data) {
        const nextAccounts = accountResp.data.data.list || []
        setAccounts(nextAccounts)
        setActiveAccountId(current => current ?? nextAccounts[0]?.id)
      }
      if (shortlistResp.data.code === 0 && shortlistResp.data.data) {
        setShortlist(shortlistResp.data.data)
      }
      if (watchlistResp.data.code === 0 && watchlistResp.data.data) {
        setWatchlistRaw(watchlistResp.data.data.wallets.join('\n'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderResearch.fetchFailed'))
    } finally {
      if (showLoading) setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [stateFilter, activeAccountId])

  useEffect(() => {
    const lastRunStatus = summary?.lastRun?.status || candidates.summary?.lastRun?.status
    if (lastRunStatus !== 'RUNNING') return

    const timer = window.setInterval(() => {
      loadAll(false)
    }, 5000)
    return () => window.clearInterval(timer)
  }, [summary?.lastRun?.status, candidates.summary?.lastRun?.status])

  const runAgent = async () => {
    setRunning(true)
    try {
      const response = await apiService.leaderResearch.run({ dryRun: false, triggerType: 'MANUAL' })
      if (response.data.code === 0) {
        message.success(t('leaderResearch.runStarted'))
        await loadAll()
      } else {
        message.error(response.data.msg || t('leaderResearch.runFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderResearch.runFailed'))
    } finally {
      setRunning(false)
    }
  }

  const openDetail = async (candidate: LeaderResearchCandidate) => {
    setDetailLoading(true)
    try {
      const response = await apiService.leaderResearch.detail({ candidateId: candidate.id })
      if (response.data.code === 0 && response.data.data) {
        setDetail(response.data.data)
      } else {
        message.error(response.data.msg || t('leaderResearch.fetchFailed'))
      }
    } finally {
      setDetailLoading(false)
    }
  }

  const openApproval = (candidate: LeaderResearchCandidate) => {
    setApprovalCandidate(candidate)
    setApprovalError(null)
    approvalForm.setFieldsValue({
      accountId: activeAccountId || accounts[0]?.id,
      autopilotEnabled: false,
      maxBudget: autopilotPolicy?.maxBudget || '25',
      singleLeaderMaxAmount: candidate.suggestedFixedAmount || autopilotPolicy?.singleLeaderMaxAmount || '5',
      maxDailyLoss: candidate.suggestedMaxDailyLoss || autopilotPolicy?.maxDailyLoss || '5',
      maxDailyOrders: candidate.suggestedMaxDailyOrders || autopilotPolicy?.maxDailyOrders || 5,
      maxPositionValue: candidate.suggestedMaxPositionValue || autopilotPolicy?.maxPositionValue || '10',
      minPrice: candidate.suggestedMinPrice || autopilotPolicy?.minPrice || '0.1',
      maxPrice: candidate.suggestedMaxPrice || autopilotPolicy?.maxPrice || '0.8'
    })
  }

  const submitApproval = async () => {
    if (!approvalCandidate) return
    const values = await approvalForm.validateFields()
    setApprovalLoading(true)
    setApprovalError(null)
    try {
      const response = await apiService.leaderResearch.createDisabledTrialConfig({
        candidateId: approvalCandidate.id,
        accountId: values.accountId,
        confirm: true,
        autopilotEnabled: values.autopilotEnabled === true,
        ...(values.autopilotEnabled === true ? {
          maxBudget: values.maxBudget,
          singleLeaderMaxAmount: values.singleLeaderMaxAmount,
          maxDailyLoss: values.maxDailyLoss,
          maxDailyOrders: Number(values.maxDailyOrders),
          maxPositionValue: values.maxPositionValue,
          minPrice: values.minPrice,
          maxPrice: values.maxPrice
        } : {})
      })
      if (response.data.code === 0) {
        message.success(values.autopilotEnabled ? t('leaderResearch.autopilotTrialCreated') : t('leaderResearch.approvalCreated'))
        setApprovalError(null)
        setApprovalCandidate(null)
        await loadAll()
      } else {
        const errorText = responseError(response.data.code, response.data.msg, t('leaderResearch.approvalFailed'))
        setApprovalError(errorText)
        message.error(errorText)
      }
    } catch (error: any) {
      const errorText = error.message || t('leaderResearch.approvalFailed')
      setApprovalError(errorText)
      message.error(errorText)
    } finally {
      setApprovalLoading(false)
    }
  }

  const previewWatchlist = async () => {
    setWatchlistLoading(true)
    try {
      const response = await apiService.leaderResearch.previewWatchlist({ rawWallets: watchlistRaw })
      if (response.data.code === 0 && response.data.data) {
        setWatchlistPreview(response.data.data)
      } else {
        message.error(responseError(response.data.code, response.data.msg, t('leaderResearch.watchlistPreviewFailed')))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderResearch.watchlistPreviewFailed'))
    } finally {
      setWatchlistLoading(false)
    }
  }

  const saveWatchlist = async () => {
    setWatchlistLoading(true)
    try {
      const response = await apiService.leaderResearch.saveWatchlist({ rawWallets: watchlistRaw, confirm: true })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('leaderResearch.watchlistSaved'))
        setWatchlistPreview(null)
        setWatchlistRaw(response.data.data.wallets.join('\n'))
        await loadAll(false)
      } else {
        message.error(responseError(response.data.code, response.data.msg, t('leaderResearch.watchlistSaveFailed')))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderResearch.watchlistSaveFailed'))
    } finally {
      setWatchlistLoading(false)
    }
  }

  const activeSummary = summary || candidates.summary || summaryFallback
  const pendingDecisions = candidates.list.filter(candidate => candidate.researchState === 'TRIAL_READY')
  const lastRun = activeSummary.lastRun
  const activeApprovalPreview = approvalPreview(approvalCandidate)
  const autopilotPolicy = shortlist?.autopilot

  const columns = [
    {
      title: t('leaderResearch.wallet'),
      key: 'wallet',
      width: 260,
      render: (_: unknown, item: LeaderResearchCandidate) => (
        <Space direction="vertical" size={0}>
          <Text strong>{item.leaderName || item.normalizedWallet.slice(0, 10)}</Text>
          <Text copyable type="secondary" style={{ fontSize: 12, fontFamily: 'monospace' }}>
            {item.normalizedWallet}
          </Text>
        </Space>
      )
    },
    {
      title: t('common.status'),
      dataIndex: 'researchState',
      width: 130,
      render: (state: LeaderResearchState) => (
        <Space direction="vertical" size={0}>
          <Tag color={STATE_COLORS[state]}>{t(`leaderResearch.states.${state}`, { defaultValue: state })}</Tag>
          {state === 'TRIAL_READY' && (
            <Text type="secondary" style={{ fontSize: 12 }}>{t('leaderResearch.trialReadyHint')}</Text>
          )}
        </Space>
      )
    },
    {
      title: t('leaderResearch.score'),
      dataIndex: 'score',
      width: 100,
      render: (score?: string) => <Text strong>{score || '-'}</Text>
    },
    {
      title: t('leaderResearch.paper'),
      key: 'paper',
      width: 220,
      render: (_: unknown, item: LeaderResearchCandidate) => {
        const session = item.latestPaperSession
        if (!session) return <Text type="secondary">-</Text>
        return (
          <Space direction="vertical" size={0}>
            <Text>{t('leaderResearch.copyablePnl')}: {session.copyablePnl}</Text>
            <Text type="secondary">{t('leaderResearch.trades')}: {session.tradeCount} / {t('leaderResearch.filtered')}: {session.filteredCount}</Text>
          </Space>
        )
      }
    },
    {
      title: t('leaderResearch.source'),
      dataIndex: 'source',
      width: 160
    },
    {
      title: t('leaderResearch.lastSeen'),
      dataIndex: 'lastSourceSeenAt',
      width: 160,
      render: (value?: number) => formatDate(value)
    },
    {
      title: t('common.actions'),
      key: 'actions',
      fixed: 'right' as const,
      width: 230,
      render: (_: unknown, item: LeaderResearchCandidate) => (
        <Space wrap>
          <Button size="small" onClick={() => openDetail(item)}>
            {t('common.detail')}
          </Button>
          {item.researchState === 'TRIAL_READY' ? (
            <Button
              size="small"
              type="primary"
              icon={<SafetyCertificateOutlined />}
              onClick={() => openApproval(item)}
            >
              {t('leaderResearch.createDisabledTrial')}
            </Button>
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('leaderResearch.observeOnly')}
            </Text>
          )}
        </Space>
      )
    }
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space align="start" style={{ justifyContent: 'space-between', width: '100%' }}>
            <div>
              <Title level={3} style={{ marginBottom: 4 }}>{t('leaderResearch.title')}</Title>
              <Paragraph type="secondary" style={{ marginBottom: 0 }}>{t('leaderResearch.subtitle')}</Paragraph>
            </div>
            <Space>
              <Button icon={<ReloadOutlined />} onClick={() => loadAll()}>{t('common.refresh')}</Button>
              <Button type="primary" icon={<PlayCircleOutlined />} loading={running || lastRun?.status === 'RUNNING'} onClick={runAgent}>
                {t('leaderResearch.runNow')}
              </Button>
            </Space>
          </Space>
          <Alert
            type="info"
            showIcon
            icon={<ExperimentOutlined />}
            message={t('leaderResearch.safetyTitle')}
            description={t('leaderResearch.safetyDesc')}
          />
          <Row gutter={[12, 12]}>
            <Col xs={24} lg={8}>
              <Card size="small" title={t('leaderResearch.autopilotStatus')}>
                <Space direction="vertical" size={4}>
                  <Tag color={autopilotPolicy?.state === 'ON' ? 'green' : autopilotPolicy?.state === 'PAUSED' ? 'orange' : 'default'}>
                    {autopilotPolicy?.state || 'OFF'}
                  </Tag>
                  <Text type="secondary">{t('leaderResearch.autopilotDefault')}</Text>
                  {autopilotPolicy?.pauseReason && <Text type="secondary">{autopilotPolicy.pauseReason}</Text>}
                </Space>
              </Card>
            </Col>
            <Col xs={24} lg={16}>
              <Card size="small" title={t('leaderResearch.activeAccount')}>
                <Select
                  style={{ width: '100%' }}
                  placeholder={t('leaderPool.selectAccount')}
                  value={activeAccountId}
                  onChange={setActiveAccountId}
                  options={accounts.map(account => ({
                    value: account.id,
                    label: `${account.accountName || account.walletAddress} (${account.proxyAddress?.slice(0, 8)}...)`
                  }))}
                />
              </Card>
            </Col>
          </Row>
          {activeSummary.sourceLimitations?.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message={t('leaderResearch.sourceLimitations')}
              description={activeSummary.sourceLimitations.join(' | ')}
            />
          )}
        </Space>
      </Card>

      <Card title={t('leaderResearch.shortlistTitle')}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <ShortlistSection
            title={t('leaderResearch.readyToTrial')}
            cards={shortlist?.readyToTrial || []}
            empty={t('leaderResearch.noReadyToTrial')}
            onDetail={openDetail}
            onApprove={openApproval}
            ctaLabel={t('leaderResearch.createTrialChoice')}
          />
          <ShortlistSection
            title={t('leaderResearch.promisingPaper')}
            cards={shortlist?.promisingPaper || []}
            empty={t('leaderResearch.noPromisingPaper')}
            onDetail={openDetail}
            onApprove={openApproval}
            ctaLabel={t('leaderResearch.createTrialChoice')}
          />
          <ShortlistSection
            title={t('leaderResearch.newCandidates')}
            cards={shortlist?.newCandidates || []}
            empty={t('leaderResearch.noNewCandidates')}
            onDetail={openDetail}
            onApprove={openApproval}
            ctaLabel={t('leaderResearch.createTrialChoice')}
          />
          <ShortlistSection
            title={t('leaderResearch.blockedOrCooling')}
            cards={shortlist?.blockedOrCooling || []}
            empty={t('leaderResearch.noBlockedOrCooling')}
            onDetail={openDetail}
            onApprove={openApproval}
            ctaLabel={t('leaderResearch.createTrialChoice')}
          />
          {shortlist?.emptyReasons?.length ? (
            <Alert type="warning" showIcon message={t('leaderResearch.emptyReasons')} description={shortlist.emptyReasons.join(' | ')} />
          ) : null}
        </Space>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.DISCOVERED')} value={activeSummary.discoveredCount} /></Card></Col>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.CANDIDATE')} value={activeSummary.candidateCount} /></Card></Col>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.PAPER')} value={activeSummary.paperCount} /></Card></Col>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.TRIAL_READY')} value={activeSummary.trialReadyCount} /></Card></Col>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.COOLDOWN')} value={activeSummary.cooldownCount} /></Card></Col>
        <Col xs={24} sm={12} lg={4}><Card><Statistic title={t('leaderResearch.states.RETIRED')} value={activeSummary.retiredCount} /></Card></Col>
      </Row>

      <Card title={t('leaderResearch.watchlistTitle')}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="info" showIcon message={t('leaderResearch.watchlistHelp')} />
          <Input.TextArea
            rows={5}
            value={watchlistRaw}
            onChange={event => setWatchlistRaw(event.target.value)}
            placeholder={t('leaderResearch.watchlistPlaceholder')}
          />
          <Space wrap>
            <Button loading={watchlistLoading} onClick={previewWatchlist}>
              {t('leaderResearch.previewWatchlist')}
            </Button>
            <Button type="primary" loading={watchlistLoading} onClick={saveWatchlist}>
              {t('leaderResearch.saveWatchlist')}
            </Button>
          </Space>
          {watchlistPreview && (
            <Row gutter={[12, 12]}>
              <WatchlistPreviewStat title={t('leaderResearch.validWallets')} values={watchlistPreview.valid} color="green" />
              <WatchlistPreviewStat title={t('leaderResearch.invalidWallets')} values={watchlistPreview.invalid} color="red" />
              <WatchlistPreviewStat title={t('leaderResearch.duplicateWallets')} values={watchlistPreview.duplicate} color="orange" />
              <WatchlistPreviewStat title={t('leaderResearch.existingCandidates')} values={watchlistPreview.existingCandidates} color="blue" />
              <WatchlistPreviewStat title={t('leaderResearch.lockedCandidates')} values={watchlistPreview.lockedCandidates} color="volcano" />
              <WatchlistPreviewStat title={t('leaderResearch.retiredCandidates')} values={watchlistPreview.retiredCandidates} color="default" />
            </Row>
          )}
        </Space>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title={t('leaderResearch.runStatus')}>
            {lastRun ? (
              <Descriptions size="small" column={1}>
                <Descriptions.Item label={t('common.status')}>
                  <Tag color={lastRun.partialFailure ? 'orange' : lastRun.status === 'SUCCESS' ? 'green' : 'default'}>{lastRun.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('leaderResearch.lastRun')}>{formatDate(lastRun.startedAt)}</Descriptions.Item>
                <Descriptions.Item label={t('leaderResearch.duration')}>{lastRun.durationMs ?? '-'} ms</Descriptions.Item>
                <Descriptions.Item label={t('leaderResearch.sourceCounts')}>{lastRun.sourceCountsJson || '-'}</Descriptions.Item>
                <Descriptions.Item label={t('leaderResearch.candidateCounts')}>{lastRun.candidateCountsJson || '-'}</Descriptions.Item>
                {(lastRun.errorMessage || lastRun.skippedReason) && (
                  <Descriptions.Item label={t('leaderResearch.reason')}>{lastRun.errorMessage || lastRun.skippedReason}</Descriptions.Item>
                )}
              </Descriptions>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderResearch.noRuns')} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title={t('leaderResearch.pendingDecisions')}>
            {pendingDecisions.length > 0 ? (
              <Space direction="vertical" style={{ width: '100%' }}>
                {pendingDecisions.slice(0, 5).map(candidate => (
                  <Card key={candidate.id} size="small">
                    <Space style={{ justifyContent: 'space-between', width: '100%' }} wrap>
                      <Space direction="vertical" size={0}>
                        <Text strong>{candidate.leaderName || candidate.normalizedWallet.slice(0, 10)}</Text>
                        <Text type="secondary">{t('leaderResearch.trialReadyHint')}</Text>
                      </Space>
                      <Button size="small" type="primary" loading={approvalLoading && approvalCandidate?.id === candidate.id} onClick={() => openApproval(candidate)}>
                        {t('leaderResearch.createDisabledTrial')}
                      </Button>
                    </Space>
                  </Card>
                ))}
              </Space>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderResearch.noPendingDecisions')} />
            )}
          </Card>
        </Col>
      </Row>

      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <Select
              allowClear
              style={{ width: 220 }}
              placeholder={t('leaderResearch.filterState')}
              value={stateFilter}
              onChange={setStateFilter}
              options={Object.keys(STATE_COLORS).map(state => ({
                value: state,
                label: t(`leaderResearch.states.${state}`, { defaultValue: state })
              }))}
            />
            <Input.Search
              allowClear
              style={{ width: 320 }}
              placeholder={t('leaderResearch.searchPlaceholder')}
              value={query}
              onChange={event => setQuery(event.target.value)}
              onSearch={() => loadAll()}
            />
          </Space>
          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={candidates.list}
            scroll={{ x: 1300 }}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('leaderResearch.empty')} /> }}
          />
        </Space>
      </Card>

      <Card title={t('leaderResearch.sourceHealth')}>
        <Row gutter={[12, 12]}>
          {sourceHealth.map(source => (
            <Col xs={24} md={12} lg={6} key={source.sourceType}>
              <Card size="small">
                <Space direction="vertical" size={4}>
                  <Badge status={source.status === 'SUCCESS' ? 'success' : source.status === 'DISABLED' ? 'default' : 'warning'} text={source.sourceType} />
                  <Tag>{source.status}</Tag>
                  <Text type="secondary">{t('leaderResearch.candidates')}: {source.lastCandidateCount}</Text>
                  <Text type="secondary">{formatDate(source.lastRunAt)}</Text>
                  {(source.disabledReason || source.errorMessage) && <Text type="secondary">{source.disabledReason || source.errorMessage}</Text>}
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      <Drawer
        width={880}
        open={!!detail}
        title={t('leaderResearch.detailTitle')}
        onClose={() => setDetail(null)}
        loading={detailLoading}
      >
        {detail && (
          <Tabs
            items={[
              {
                key: 'overview',
                label: t('common.overview'),
                children: (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Descriptions bordered column={1} size="small">
                      <Descriptions.Item label={t('leaderResearch.wallet')}>{detail.candidate.normalizedWallet}</Descriptions.Item>
                      <Descriptions.Item label={t('common.status')}>{detail.candidate.researchState}</Descriptions.Item>
                      <Descriptions.Item label={t('leaderResearch.score')}>{detail.candidate.score || '-'}</Descriptions.Item>
                      <Descriptions.Item label={t('leaderResearch.reason')}>{detail.candidate.reason || '-'}</Descriptions.Item>
                      <Descriptions.Item label={t('leaderResearch.riskFlags')}>{detail.candidate.riskFlags.join(', ') || '-'}</Descriptions.Item>
                      <Descriptions.Item label={t('leaderResearch.sourceEvidence')}>{detail.candidate.sourceEvidence || '-'}</Descriptions.Item>
                    </Descriptions>
                    {detail.latestScore && (
                      <Descriptions bordered size="small" column={2} title={t('leaderResearch.scoreBreakdown')}>
                        <Descriptions.Item label="profit">{detail.latestScore.profitSignal}</Descriptions.Item>
                        <Descriptions.Item label="repeatability">{detail.latestScore.repeatability}</Descriptions.Item>
                        <Descriptions.Item label="liquidity">{detail.latestScore.liquidityFit}</Descriptions.Item>
                        <Descriptions.Item label="entry">{detail.latestScore.entryPriceFit}</Descriptions.Item>
                        <Descriptions.Item label="slippage">{detail.latestScore.slippageRisk}</Descriptions.Item>
                        <Descriptions.Item label="drawdown">{detail.latestScore.drawdownRisk}</Descriptions.Item>
                      </Descriptions>
                    )}
                  </Space>
                )
              },
              {
                key: 'trades',
                label: t('leaderResearch.paperTrades'),
                children: <PaperTradeTable trades={detail.paperTrades} />
              },
              {
                key: 'positions',
                label: t('leaderResearch.paperPositions'),
                children: <PaperPositionTable positions={detail.paperPositions} />
              },
              {
                key: 'events',
                label: t('leaderResearch.events'),
                children: (
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={detail.events}
                    columns={[
                      { title: t('common.time'), dataIndex: 'createdAt', render: formatDate },
                      { title: t('leaderResearch.eventType'), dataIndex: 'eventType' },
                      { title: t('leaderResearch.reason'), dataIndex: 'reason' }
                    ]}
                  />
                )
              }
            ]}
          />
        )}
      </Drawer>

      <Modal
        open={!!approvalCandidate}
        title={t('leaderResearch.createDisabledTrial')}
        onCancel={() => {
          setApprovalCandidate(null)
          setApprovalError(null)
        }}
        onOk={submitApproval}
        confirmLoading={approvalLoading}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          {approvalError && (
            <Alert
              type="error"
              showIcon
              message={t('leaderResearch.approvalFailed')}
              description={approvalError}
            />
          )}
          <Alert
            type="warning"
            showIcon
            message={t('leaderResearch.approvalSafetyTitle')}
            description={t('leaderResearch.approvalSafetyDesc')}
          />
            <Form form={approvalForm} layout="vertical">
            <Descriptions bordered size="small" column={1} title={t('leaderResearch.approvalPreview')}>
              <Descriptions.Item label={t('leaderResearch.fixedAmount')}>{activeApprovalPreview.fixedAmount}</Descriptions.Item>
              <Descriptions.Item label={t('leaderResearch.maxDailyLoss')}>{activeApprovalPreview.maxDailyLoss}</Descriptions.Item>
              <Descriptions.Item label={t('leaderResearch.maxDailyOrders')}>{activeApprovalPreview.maxDailyOrders}</Descriptions.Item>
              <Descriptions.Item label={t('leaderResearch.priceRange')}>{activeApprovalPreview.priceRange}</Descriptions.Item>
              <Descriptions.Item label={t('leaderResearch.maxPositionValue')}>{activeApprovalPreview.maxPositionValue}</Descriptions.Item>
            </Descriptions>
            <Form.Item name="accountId" label={t('leaderPool.account')} rules={[{ required: true, message: t('leaderPool.selectAccount') }]}>
              <Select
                options={accounts.map(account => ({
                  value: account.id,
                  label: `${account.accountName || account.walletAddress} (${account.proxyAddress?.slice(0, 8)}...)`
                }))}
              />
            </Form.Item>
            <Form.Item
              name="autopilotEnabled"
              valuePropName="checked"
              label={t('leaderResearch.autopilotTrialSwitch')}
              extra={t('leaderResearch.autopilotTrialSwitchHelp')}
            >
              <Switch />
            </Form.Item>
            {autopilotApprovalEnabled && (
              <>
                <Alert
                  type="error"
                  showIcon
                  message={t('leaderResearch.autopilotBudgetConfirmTitle')}
                  description={t('leaderResearch.autopilotBudgetConfirmDesc')}
                />
                <Row gutter={12}>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="maxBudget"
                      label={t('leaderResearch.maxBudget')}
                      rules={[{ required: true, message: t('leaderResearch.maxBudgetRequired') }]}
                    >
                      <Input suffix="USDC" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="singleLeaderMaxAmount"
                      label={t('leaderResearch.singleLeaderMaxAmount')}
                      rules={[{ required: true, message: t('leaderResearch.singleLeaderMaxAmountRequired') }]}
                    >
                      <Input suffix="USDC" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="maxDailyLoss"
                      label={t('leaderResearch.maxDailyLoss')}
                      rules={[{ required: true, message: t('leaderResearch.maxDailyLossRequired') }]}
                    >
                      <Input suffix="USDC" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="maxDailyOrders"
                      label={t('leaderResearch.maxDailyOrders')}
                      rules={[{ required: true, message: t('leaderResearch.maxDailyOrdersRequired') }]}
                    >
                      <Input type="number" min={1} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="maxPositionValue"
                      label={t('leaderResearch.maxPositionValue')}
                      rules={[{ required: true, message: t('leaderResearch.maxPositionValueRequired') }]}
                    >
                      <Input suffix="USDC" />
                    </Form.Item>
                  </Col>
                  <Col xs={12} md={6}>
                    <Form.Item name="minPrice" label={t('leaderResearch.minPrice')}>
                      <Input />
                    </Form.Item>
                  </Col>
                  <Col xs={12} md={6}>
                    <Form.Item name="maxPrice" label={t('leaderResearch.maxPrice')}>
                      <Input />
                    </Form.Item>
                  </Col>
                </Row>
              </>
            )}
          </Form>
        </Space>
      </Modal>
    </Space>
  )
}

const PaperTradeTable: React.FC<{ trades: LeaderPaperTrade[] }> = ({ trades }) => (
  <Table
    rowKey="id"
    size="small"
    dataSource={trades}
    columns={[
      { title: 'Time', dataIndex: 'eventTime', render: formatDate },
      { title: 'Side', dataIndex: 'side' },
      { title: 'Market', dataIndex: 'marketTitle', render: (value?: string, item?: LeaderPaperTrade) => value || item?.marketId },
      { title: 'Leader Price', dataIndex: 'leaderPrice' },
      { title: 'Sim Amount', dataIndex: 'simulatedAmount' },
      { title: 'Filter', dataIndex: 'filterResult' },
      { title: 'Quote', dataIndex: 'quoteConfidence' },
      { title: 'Valuation', dataIndex: 'valuationStatus', render: valuationTag }
    ]}
  />
)

const PaperPositionTable: React.FC<{ positions: LeaderPaperPosition[] }> = ({ positions }) => (
  <Table
    rowKey="id"
    size="small"
    dataSource={positions}
    columns={[
      { title: 'Market', dataIndex: 'marketId' },
      { title: 'Outcome', dataIndex: 'outcome' },
      { title: 'Qty', dataIndex: 'quantity' },
      { title: 'Cost', dataIndex: 'cost' },
      { title: 'Value', dataIndex: 'currentValue' },
      { title: 'PnL', dataIndex: 'unrealizedPnl' },
      { title: 'Quote', dataIndex: 'quoteConfidence' },
      { title: 'Valuation', dataIndex: 'valuationStatus', render: valuationTag }
    ]}
  />
)

const toneColor = (tone?: string) => {
  if (tone === 'good') return 'green'
  if (tone === 'warning') return 'orange'
  if (tone === 'danger') return 'red'
  return 'default'
}

const ShortlistSection: React.FC<{
  title: string
  cards: LeaderResearchShortlistCard[]
  empty: string
  ctaLabel: string
  onDetail: (candidate: LeaderResearchCandidate) => void
  onApprove: (candidate: LeaderResearchCandidate) => void
}> = ({ title, cards, empty, ctaLabel, onDetail, onApprove }) => (
  <Card size="small" title={title}>
    {cards.length > 0 ? (
      <Row gutter={[12, 12]}>
        {cards.map(card => (
          <Col xs={24} md={12} xl={8} key={`${card.group}-${card.candidate.id}`}>
            <Card size="small">
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <Space style={{ justifyContent: 'space-between', width: '100%' }} align="start">
                  <Space direction="vertical" size={0}>
                    <Text strong>{card.candidate.leaderName || card.candidate.normalizedWallet.slice(0, 10)}</Text>
                    <Text copyable type="secondary" style={{ fontSize: 12, fontFamily: 'monospace' }}>
                      {card.candidate.normalizedWallet}
                    </Text>
                  </Space>
                  <Tag color="blue">#{card.priorityRank}</Tag>
                </Space>
                <Text>{card.recommendationReason}</Text>
                {card.riskReason && <Alert type="warning" showIcon message={card.riskReason} />}
                <Space wrap>
                  {card.evidence.map(metric => (
                    <Tag key={`${metric.label}-${metric.value}`} color={toneColor(metric.tone)}>
                      {metric.label}: {metric.value}
                    </Tag>
                  ))}
                </Space>
                <Descriptions size="small" column={1}>
                  <Descriptions.Item label="Fixed">{usdc(card.suggestedConfig.fixedAmount)}</Descriptions.Item>
                  <Descriptions.Item label="Daily loss">{usdc(card.suggestedConfig.maxDailyLoss)}</Descriptions.Item>
                  <Descriptions.Item label="Orders">{card.suggestedConfig.maxDailyOrders ?? '-'}</Descriptions.Item>
                </Descriptions>
                <Space>
                  <Button size="small" onClick={() => onDetail(card.candidate)}>
                    Detail
                  </Button>
                  {card.canCreateDisabledTrial && (
                    <Button
                      size="small"
                      type="primary"
                      onClick={() => onApprove(card.candidate)}
                    >
                      {ctaLabel}
                    </Button>
                  )}
                </Space>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>
    ) : (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={empty} />
    )}
  </Card>
)

const WatchlistPreviewStat: React.FC<{
  title: string
  values: string[]
  color: string
}> = ({ title, values, color }) => (
  <Col xs={24} md={12} xl={8}>
    <Card size="small" title={`${title} (${values.length})`}>
      {values.length > 0 ? (
        <Space wrap>
          {values.slice(0, 12).map(value => (
            <Tag key={`${title}-${value}`} color={color}>{value}</Tag>
          ))}
          {values.length > 12 && <Tag>+{values.length - 12}</Tag>}
        </Space>
      ) : (
        <Text type="secondary">-</Text>
      )}
    </Card>
  </Col>
)

const summaryFallback: LeaderResearchSummary = {
  discoveredCount: 0,
  candidateCount: 0,
  paperCount: 0,
  trialReadyCount: 0,
  cooldownCount: 0,
  retiredCount: 0,
  activePaperSessions: 0,
  pendingRiskCount: 0,
  sourceLimitations: []
}

export default LeaderResearch
