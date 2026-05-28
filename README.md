# MSA-Net 高并发多模态情感分析平台

MSA-Net 是一个面向文本、音频、视频的多模态情感分析系统。本仓库在原有 MSA 推理能力基础上，补齐了 Spring Boot 后端、前端页面、异步任务队列、Redis 限流、MySQL 任务状态持久化、Python 推理 Worker 和 Docker Compose 一键部署，提供可演示、可压测、可部署的工程化服务。

仓库地址：<https://github.com/sunshinebigboy2023/MSA-Net>

克隆仓库：

```bash
git clone https://github.com/sunshinebigboy2023/MSA-Net.git
```

## 项目亮点

- 多模态情感分析：支持文本输入和视频上传，后续由 MSA worker 进行特征提取与情感推理。
- 高并发削峰：请求进入后端后快速写入 MySQL，并投递到 RabbitMQ，避免接口被长耗时推理阻塞。
- 异步任务状态机：任务包含 `QUEUED`、`RUNNING`、`SUCCESS`、`FAILED`、`RETRYING`、`DEAD_LETTER` 等状态。
- Redis 限流与缓存：使用 Redis Lua 脚本限制提交速率，并缓存任务状态。
- 失败隔离：RabbitMQ 配置重试队列和死信队列，异常任务不会拖垮主队列。
- 容器化部署：Docker Compose 一键启动 MySQL、Redis、RabbitMQ、Java 后端和 Python worker。
- 前端状态展示：分析任务提交后可轮询任务状态，展示排队、运行、成功、失败等进度。

## 技术栈

```text
前端：React / Ant Design Pro
后端：Spring Boot / MyBatis Plus / MySQL / Redis / RabbitMQ
推理：Python / PyTorch CPU / Transformers / FFmpeg / MSA-Net
部署：Docker Compose / Nginx 可选
压测：Python ThreadPoolExecutor 压测脚本
```

## 目录结构

```text
MSA-Net
├── MSA/                                  Python MSA 推理服务和 worker
│   ├── GCNet/
│   ├── DT-MSA/
│   ├── Dockerfile.worker
│   └── requirements-standalone.txt
├── Net/
│   ├── user-center-backend-master/       Spring Boot 后端
│   └── user-center-frontend-master/      React 前端
├── docs/                                 架构、演示和部署文档
├── scripts/                              压测脚本和 mock server / mock worker
├── docker-compose.yml                    高并发运行环境
└── .env.example                          环境变量示例
```

## 高并发架构

```text
用户提交分析任务
        |
        v
Spring Boot API
        |
        +--> Redis Lua 限流
        |
        +--> MySQL 写入 analysis_task
        |
        +--> RabbitMQ 投递任务
                  |
                  v
        Python MSA Worker 多线程消费
                  |
                  v
        MSA-Net 多模态推理
                  |
                  v
        Callback 回写 Java 后端
                  |
                  v
        MySQL / Redis 更新任务结果
```

这种设计把“提交请求”和“模型推理”解耦。接口层负责快速接收请求，RabbitMQ 负责削峰，worker 按自身算力稳定消费队列。

## 本地 Docker 启动

确保已经安装 Docker Desktop / Docker Compose，然后在仓库根目录执行：

```powershell
cp .env.example .env
docker compose up -d --build
```

启动后服务地址：

```text
后端 API：http://localhost:8080/api
RabbitMQ 管理台：http://localhost:15672
RabbitMQ 账号：见 `.env`
Redis：localhost:6379
MySQL：容器内 `yupi` 数据库，默认应用账号见 `.env`
```

查看服务状态：

```powershell
docker compose ps
```

查看队列堆积：

```powershell
docker exec msa-net-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers
```

查看任务状态分布：

```powershell
docker exec msa-net-mysql mysql -uroot -pmsa_root yupi -e "select status, count(*) cnt from analysis_task group by status;"
```

## 压测结果说明

README 中的 `1000 请求 / 100 并发，业务成功率 100%，QPS 227.58，平均耗时 266.82ms，P95 476ms` 指的是“任务提交接口 / 提交链路”的压测结果，不是完整模型推理的端到端 QPS。

这组数据主要验证以下链路在高并发下是否稳定：

- Spring Boot 接口接收请求
- MySQL 写入 `analysis_task`
- Redis Lua 限流
- RabbitMQ 投递任务
- 异步削峰和排队能力

它不代表完整视频推理吞吐量。实际模型推理耗时还会受到视频长度、CPU/GPU 性能、特征提取耗时、模型大小和 worker 并发度的影响。

## 模型文件与本地复现说明

以下目录通常包含模型、工具、临时文件或运行产物，大文件不建议提交到 GitHub：

```text
MSA/tools/
MSA/models/
MSA/temp/
MSA/output/
MSA/dataset/
MSA/.venv/
```

- `MSA/models/`：训练好的 checkpoint、权重文件、Tokenizer 或推理所需模型目录。
- `MSA/tools/`：FFmpeg、OpenSmile、OpenFace 或其他特征提取外部依赖。
- `MSA/temp/`：上传后和处理中产生的临时文件。
- `MSA/output/`：推理结果导出目录，便于调试和复盘。

完整推理时，需要先手动准备 `MSA/models/` 和 `MSA/tools/`。Docker Compose 会把这两个目录挂载到 worker 容器中，避免把大模型直接打进镜像。

如果暂时没有模型文件，可以用 mock worker 跑通“前端 -> 后端 -> MySQL/Redis -> RabbitMQ -> callback -> 结果查询”的全链路：

```bash
python scripts/mock_msa_worker.py \
  --rabbitmq-host 127.0.0.1 \
  --rabbitmq-port 5672 \
  --rabbitmq-username "$RABBITMQ_DEFAULT_USER" \
  --rabbitmq-password "$RABBITMQ_DEFAULT_PASS" \
  --callback-base-url http://127.0.0.1:8080/api \
  --callback-token "$MSA_CALLBACK_TOKEN"
```

这个脚本不会加载真实模型，只会消费 RabbitMQ 队列、先回调 `RUNNING`，再回调 `SUCCESS`，用于演示异步任务链路和前端状态展示。

## 压测结果

本地 Docker 环境下，已完成真实业务提交链路压测。压测脚本会校验业务返回码 `code == 0` 和 `taskId`，不会把“未登录但 HTTP 200”的响应误判为成功。

```text
1000 请求 / 100 并发
业务成功率：100%
QPS：227.58
平均耗时：266.82 ms
P95：476 ms
最大耗时：2528 ms
RabbitMQ DLQ：0
```

压测后可以观察到 RabbitMQ 队列堆积并由 worker 持续消费，说明系统具备异步削峰能力。更详细的演示步骤见：

- `docs/demo/high-concurrency-demo.md`
- `docs/msa-net-high-concurrency-resume.md`

## 压测命令

先登录系统获取 `JSESSIONID`，然后执行：

```powershell
python .\scripts\load_test_analysis.py `
  --base-url http://127.0.0.1:8080/api `
  --total 1000 `
  --concurrency 100 `
  --cookie "JSESSIONID=你的登录会话"
```

压测输出示例：

```json
{
  "total": 1000,
  "ok": 1000,
  "failed": 0,
  "successRate": 1.0,
  "avgMs": 266.82,
  "p95Ms": 476,
  "maxMs": 2528,
  "qps": 227.58
}
```

## 安全与可靠性补充

- 任务状态和结果查询现在会同时校验 `taskId + userId`，普通用户不能通过猜测 `taskId` 查看别人的任务。
- 视频上传在业务层增加了空文件、大小、后缀和 MIME 白名单校验，默认最大 200MB，可用 `MSA_UPLOAD_MAX_VIDEO_SIZE_MB` 调整。
- callback 增加终态保护，`SUCCESS`、`FAILED`、`DEAD_LETTER` 不会被重复或乱序回调覆盖。
- MySQL、RabbitMQ、callback token 等敏感项改为从环境变量读取，示例见 `.env.example`。

## 普通本地开发启动

不使用高并发 Docker 链路时，可以分别启动前端、后端和 MSA HTTP 服务。仓库里保留了本地启动脚本：

```powershell
.\start-all.bat -Restart
```

常用地址：

```text
前端：http://localhost:8001/
后端：http://localhost:8080/api
MSA HTTP 服务：http://127.0.0.1:8000
```

## 验证命令

Python worker 与压测脚本：

```powershell
cd MSA
python -m unittest tests.test_runtime_packaging tests.test_worker_payload tests.test_load_test_analysis -v
```

后端核心测试：

```powershell
cd Net\user-center-backend-master
.\mvnw.cmd test "-Dtest=AnalysisControllerTest,AnalysisServiceImplTest,AnalysisTaskServiceSecurityTest,AnalysisTaskServiceCleanupTest,HttpMsaClientTest,MsaPropertiesTest,AnalysisQueueProducerTest,AnalysisServiceImplAsyncTest"
```

前端构建：

```powershell
cd Net\user-center-frontend-master
npm.cmd run build
```

Docker Compose 配置检查：

```powershell
docker compose config --quiet
```

## ECS Deployment (Single Host, Public IP)

This repository can be deployed on one Alibaba Cloud ECS instance with Docker Compose.

Recommended public entrypoint:

```text
MSA-Net: http://<ECS-IP>:5020
```

This port plan avoids conflict with the existing `reminder` service on port `5019`.

### Public ports

Open these ECS security-group ports:

```text
5020/tcp    MSA-Net web entrypoint
15673/tcp   Optional RabbitMQ management UI
```

Do not expose these services to the public Internet:

```text
3306  MySQL
6379  Redis
5672  RabbitMQ AMQP
8080  Spring Boot backend
```

### Before starting

Place required local runtime assets back into these directories on the ECS host:

```text
MSA/models/
MSA/tools/
```

The worker container mounts them read-only at runtime.

### Start on ECS

```powershell
docker compose up -d --build
```

After startup:

```text
Frontend + API gateway: http://<ECS-IP>:5020
RabbitMQ UI (optional): http://<ECS-IP>:15673
```

### Runtime cleanup

This project now cleans inference artifacts automatically:

- Worker temp files under task-level `temp/<taskId>/...` are deleted after each task, both on success and failure.
- Uploaded original videos are deleted after the backend task reaches a terminal state:
  - `SUCCESS`
  - `FAILED`
  - `DEAD_LETTER`

Uploads are not deleted while a task is still retryable, so async retries remain safe.
