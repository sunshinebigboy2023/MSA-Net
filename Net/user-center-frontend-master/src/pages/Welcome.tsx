import React, { useEffect, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-layout';
import type { UploadFile } from 'antd/es/upload/interface';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  message,
  Progress,
  Radio,
  Row,
  Space,
  Statistic,
  Switch,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import {
  CheckCircleOutlined,
  CloudUploadOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  VideoCameraOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {
  getAnalysisResult,
  getAnalysisTask,
  submitAnalysis,
} from '@/services/ant-design-pro/api';
import { buildAnalysisFormData } from './analysisForm';
import {
  analysisStatusPercent,
  analysisStatusText,
  isAnalysisFailed,
  isAnalysisSucceeded,
} from './analysisTaskStatus';
import styles from './Welcome.less';

const { Dragger } = Upload;
const { TextArea } = Input;

type LanguageMode = 'auto' | 'zh' | 'en';

const statusPercent: Record<string, number> = {
  PENDING: 16,
  PREPROCESSING: 36,
  EXTRACTING: 58,
  INFERRING: 82,
  SUCCESS: 100,
  FAILED: 100,
};

const statusText: Record<string, string> = {
  PENDING: '任务已创建，等待调度',
  PREPROCESSING: '正在预处理媒体文件',
  EXTRACTING: '正在提取文本、音频和视频特征',
  INFERRING: '模型正在进行情感推理',
  SUCCESS: '分析完成',
  FAILED: '分析失败',
};

const statusDescription: Record<string, string> = {
  PENDING: '系统已收到输入，马上开始处理。',
  PREPROCESSING: '正在检查视频音轨、抽取音频并准备特征输入。',
  EXTRACTING: '多模态特征会决定最终使用的模型条件。',
  INFERRING: '正在选择语言数据集和模态 checkpoint。',
  SUCCESS: '可以查看情感极性、置信度和推理细节。',
  FAILED: '请检查输入文件、MSA 服务状态或模型文件。',
};

const polarityText: Record<string, string> = {
  positive: '积极',
  negative: '消极',
  neutral: '中性',
};

const polarityColor: Record<string, string> = {
  positive: '#1f8f5f',
  negative: '#c2413d',
  neutral: '#6b7280',
};

const modalityText: Record<string, string> = {
  text: '文本',
  audio: '音频',
  video: '视频',
};

const featureText: Record<string, string> = {
  missing: '未提供',
  provided: '已提供',
  extracted: '已提取',
  unavailable: '不可用',
  extracted_from_transcript: '来自转写',
  extracted_with_transcript: '文本+转写',
};

const confidenceLevel = (confidence?: number) => {
  const value = confidence || 0;
  if (value >= 0.72) {
    return { label: '可信', color: 'green' };
  }
  if (value >= 0.58) {
    return { label: '需复核', color: 'gold' };
  }
  return { label: '低置信', color: 'red' };
};

const Welcome: React.FC = () => {
  const [text, setText] = useState('');
  const [language, setLanguage] = useState<LanguageMode>('auto');
  const [enhanceTextWithTranscript, setEnhanceTextWithTranscript] = useState(true);
  const [videoList, setVideoList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [task, setTask] = useState<API.AnalysisTask>();
  const [result, setResult] = useState<API.AnalysisResult>();
  const timerRef = useRef<number>();

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        window.clearInterval(timerRef.current);
      }
    };
  }, []);

  const stopPolling = () => {
    if (timerRef.current) {
      window.clearInterval(timerRef.current);
      timerRef.current = undefined;
    }
  };

  const pollTask = (taskId: string) => {
    stopPolling();
    timerRef.current = window.setInterval(async () => {
      try {
        const current = await getAnalysisTask(taskId);
        setTask(current);
        if (isAnalysisSucceeded(current.status)) {
          stopPolling();
          const nextResult = await getAnalysisResult(taskId);
          setResult(nextResult);
          setSubmitting(false);
        }
        if (isAnalysisFailed(current.status)) {
          stopPolling();
          setSubmitting(false);
          message.error(current.error || '分析失败，请检查 MSA 服务状态');
        }
      } catch (error) {
        stopPolling();
        setSubmitting(false);
        message.error('获取任务状态失败，请确认后端和 MSA 服务正在运行');
      }
    }, 1500);
  };

  const handleAnalyze = async () => {
    const content = text.trim();
    const video = videoList[0]?.originFileObj as File | undefined;
    if (!content && !video) {
      message.warning('请输入文本，或上传一个视频文件');
      return;
    }

    try {
      stopPolling();
      setSubmitting(true);
      setResult(undefined);
      const nextTask = await submitAnalysis(
        buildAnalysisFormData({
          text: content,
          language,
          enhanceTextWithTranscript,
          video,
          browserLanguage: window.navigator.language,
        }),
      );
      setTask(nextTask);
      pollTask(nextTask.taskId);
      message.success('任务已提交，正在分析');
    } catch (error) {
      setSubmitting(false);
      message.error('提交分析任务失败，请确认后端服务可用');
    }
  };

  const percent = task ? analysisStatusPercent(task.status, statusPercent[task.status] || 20) : 0;
  const progressStatus =
    task && isAnalysisFailed(task.status)
      ? 'exception'
      : task && isAnalysisSucceeded(task.status)
      ? 'success'
      : 'active';
  const polarity = result?.sentimentPolarity || result?.emotionLabel || '-';
  const confidence = confidenceLevel(result?.confidence);
  const confidencePercent = Math.round((result?.confidence || 0) * 100);
  const canFuseTranscript = Boolean(text.trim() && videoList.length);

  return (
    <PageContainer
      title="多模态情感分析"
      content="输入文本或上传视频，系统会自动选择中文/英文模型与可用模态，输出情感极性、置信度和推理依据。"
    >
      <div className={styles.workspace}>
        <section className={styles.heroBand}>
          <div>
            <Typography.Title level={2}>情感判断工作台</Typography.Title>
            <Typography.Paragraph>
              文本、语音和画面会共同参与判断；当文本和视频同时提供时，建议融合视频转写，提高语义覆盖率。
            </Typography.Paragraph>
          </div>
          <Space size={12} wrap>
            <Tag icon={<SafetyCertificateOutlined />} color="green">
              语言自动路由
            </Tag>
            <Tag icon={<ExperimentOutlined />} color="blue">
              多模态 checkpoint
            </Tag>
            <Tag icon={<WarningOutlined />} color="gold">
              低置信提醒
            </Tag>
          </Space>
        </section>

        <Row gutter={[24, 24]}>
          <Col xs={24} lg={12}>
            <Card className={styles.panel} title={<Space><FileTextOutlined />分析输入</Space>}>
              <Space direction="vertical" size={20} className={styles.fullWidth}>
                <div>
                  <div className={styles.fieldHeader}>
                    <Typography.Text strong>文本内容</Typography.Text>
                    <Typography.Text type="secondary">最多 2000 字</Typography.Text>
                  </div>
                  <TextArea
                    className={styles.textArea}
                    value={text}
                    onChange={(event) => setText(event.target.value)}
                    rows={8}
                    maxLength={2000}
                    showCount
                    placeholder="例如：这段发言整体很积极，但语气里有一点迟疑。"
                  />
                </div>

                <div>
                  <div className={styles.fieldHeader}>
                    <Typography.Text strong>视频文件</Typography.Text>
                    <Typography.Text type="secondary">支持 mp4、mov、avi、mkv</Typography.Text>
                  </div>
                  <Dragger
                    className={styles.uploader}
                    accept="video/*,.mp4,.avi,.mov,.mkv"
                    maxCount={1}
                    fileList={videoList}
                    beforeUpload={() => false}
                    onChange={({ fileList }) => setVideoList(fileList.slice(-1))}
                    onRemove={() => {
                      setVideoList([]);
                    }}
                  >
                    <p className="ant-upload-drag-icon">
                      <CloudUploadOutlined />
                    </p>
                    <p className="ant-upload-text">点击或拖拽视频到这里</p>
                    <p className="ant-upload-hint">上传后会尝试提取画面特征、音频特征和语音转写。</p>
                  </Dragger>
                </div>

                <div className={styles.optionGrid}>
                  <div>
                    <Typography.Text strong>语言模型</Typography.Text>
                    <Radio.Group
                      className={styles.languageGroup}
                      value={language}
                      onChange={(event) => setLanguage(event.target.value)}
                      optionType="button"
                      buttonStyle="solid"
                      options={[
                        { label: '自动识别', value: 'auto' },
                        { label: '中文 SIMS', value: 'zh' },
                        { label: '英文 MOSI', value: 'en' },
                      ]}
                    />
                  </div>
                  <div className={styles.switchPanel}>
                    <Space align="center">
                      <Switch
                        checked={enhanceTextWithTranscript}
                        disabled={!canFuseTranscript}
                        onChange={setEnhanceTextWithTranscript}
                      />
                      <Typography.Text strong>融合视频转写</Typography.Text>
                      <Tooltip title="文本和视频同时存在时，把人工文本与 ASR 转写合并后提取文本特征。">
                        <InfoCircleOutlined className={styles.helpIcon} />
                      </Tooltip>
                    </Space>
                    <Typography.Paragraph type="secondary">
                      {canFuseTranscript ? '推荐开启，可补充说话内容。' : '输入文本并上传视频后可用。'}
                    </Typography.Paragraph>
                  </div>
                </div>

                <Button
                  type="primary"
                  size="large"
                  icon={<PlayCircleOutlined />}
                  loading={submitting}
                  onClick={handleAnalyze}
                  block
                >
                  开始情感分析
                </Button>
              </Space>
            </Card>
          </Col>

          <Col xs={24} lg={12}>
            <Card className={styles.panel} title={<Space><SyncOutlined />分析结果</Space>}>
              {task ? (
                <Space direction="vertical" size={20} className={styles.fullWidth}>
                  <Alert
                    type={isAnalysisFailed(task.status) ? 'error' : isAnalysisSucceeded(task.status) ? 'success' : 'info'}
                    showIcon
                    message={analysisStatusText(task.status, statusText[task.status])}
                    description={task.error || statusDescription[task.status] || `任务编号：${task.taskId}`}
                  />
                  <Progress percent={percent} status={progressStatus} strokeColor="#1f8f5f" />

                  {result ? (
                    <>
                      <Row gutter={[16, 16]}>
                        <Col xs={24} md={8}>
                          <div className={styles.metric}>
                            <Statistic
                              title="情感极性"
                              value={polarityText[polarity] || polarity}
                              valueStyle={{ color: polarityColor[polarity] || '#111827' }}
                            />
                          </div>
                        </Col>
                        <Col xs={12} md={8}>
                          <div className={styles.metric}>
                            <Statistic title="模型分数" value={result.score ?? 0} precision={3} />
                          </div>
                        </Col>
                        <Col xs={12} md={8}>
                          <div className={styles.metric}>
                            <Statistic title="置信度" value={confidencePercent} suffix="%" />
                            <Tag color={confidence.color}>{confidence.label}</Tag>
                          </div>
                        </Col>
                      </Row>

                      {(result.warnings || []).length > 0 && (
                        <Alert
                          type="warning"
                          showIcon
                          message="需要复核"
                          description={(result.warnings || []).join('；')}
                        />
                      )}

                      <Descriptions column={1} size="small" bordered className={styles.details}>
                        <Descriptions.Item label="使用模态">
                          {(result.usedModalities || []).map((item) => (
                            <Tag color="blue" key={item}>{modalityText[item] || item}</Tag>
                          ))}
                          {(result.usedModalities || []).length === 0 && '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="缺失模态">
                          {(result.missingModalities || []).map((item) => (
                            <Tag key={item}>{modalityText[item] || item}</Tag>
                          ))}
                          {(result.missingModalities || []).length === 0 && '无'}
                        </Descriptions.Item>
                        <Descriptions.Item label="语言 / 数据集">
                          {result.language || '-'} / {result.modelDataset || '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="模型条件">{result.modelCondition || '-'}</Descriptions.Item>
                        <Descriptions.Item label="文本来源">{result.textSource || '-'}</Descriptions.Item>
                        <Descriptions.Item label="特征状态">
                          {Object.entries(result.featureStatus || {}).map(([name, status]) => (
                            <Tag key={name} icon={<CheckCircleOutlined />} color={status === 'unavailable' ? 'red' : 'green'}>
                              {modalityText[name] || name}: {featureText[status] || status}
                            </Tag>
                          ))}
                        </Descriptions.Item>
                        <Descriptions.Item label="处理耗时">
                          {result.processingTimeMs ? `${result.processingTimeMs} ms` : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="语音转写">
                          <Typography.Paragraph className={styles.transcript}>
                            {result.transcript || '无转写内容'}
                          </Typography.Paragraph>
                        </Descriptions.Item>
                      </Descriptions>
                    </>
                  ) : (
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description="任务处理中，结果会自动刷新"
                    />
                  )}
                </Space>
              ) : (
                <Empty
                  image={<VideoCameraOutlined className={styles.emptyIcon} />}
                  description="提交文本或视频后，这里会显示分析进度和结果"
                />
              )}
            </Card>
          </Col>
        </Row>
      </div>
    </PageContainer>
  );
};

export default Welcome;
