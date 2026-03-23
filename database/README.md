# DynamoDB Local 本地化部署指南

## 快速开始

```bash
# 1. 启动DynamoDB Local (Docker)
cd hw03/database
docker-compose up -d

# 2. 创建数据库表
python3 schema.py

# 3. 验证表已创建
aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-east-1
```

## 前置要求

- **Python 3.8+** - 运行表创建脚本
- **Docker & Docker Compose** - 运行DynamoDB Local
- **AWS CLI v2** - 验证连接
- **boto3** - `pip3 install boto3`

## 启动与停止

```bash
# 启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down

# 完全清理（包括数据）
docker-compose down -v
```

## 创建表

```bash
# 自动创建所有表
python3 schema.py
```

表将在以下位置创建：
- `ChatMessages` - 聊天消息表
- `UserActivity` - 用户活动表
- `RoomAnalytics` - 房间分析表

## 常见问题

### Docker Desktop 未启动

**症状：** `failed to connect to the docker API`

**解决方案：**
1. 打开 **Windows 开始菜单**，搜索 `Docker Desktop`
2. **点击启动** Docker Desktop 应用
3. 等待右下角系统托盘显示 Docker 已连接（30-60秒）
4. 重新运行：`docker-compose up -d`

### DynamoDB 连接失败

**症状：** `python3 schema.py` 挂起或超时

**解决方案：**
```bash
# 1. 验证Docker容器是否运行
docker ps | findstr dynamodb

# 2. 如果没有运行，启动它
docker-compose up -d

# 3. 等待3秒后重试
Start-Sleep -Seconds 3
python3 schema.py
```

### boto3导入失败

**症状：** `ImportError: No module named 'boto3'`

**解决方案：**
```bash
# 使用虚拟环境的Python
# (位置: .venv/Scripts/python.exe)

# 重新安装boto3
pip3 install boto3 --upgrade
```

### 清理所有数据重新开始

```bash
# 1. 停止并删除所有容器
docker-compose down -v

# 2. 清理Docker镜像（可选）
docker rmi amazon/dynamodb-local:latest

# 3. 重新启动
docker-compose up -d
Start-Sleep -Seconds 2
python3 schema.py
```

## Java应用配置

在 `application.properties` 中设置：

```properties
aws.dynamodb.endpoint-url=http://localhost:8000
aws.dynamodb.region=us-east-1
aws.dynamodb.access-key=test
aws.dynamodb.secret-key=test
```

- [AWS DynamoDB Local Documentation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
- [AWS CLI DynamoDB Commands](https://docs.aws.amazon.com/cli/latest/reference/dynamodb/)
- [boto3 DynamoDB Documentation](https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/dynamodb.html)
- [DynamoDB Query and Scan](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.html)

---

## 支持

如遇到问题，请检查：
1. ✅ Python/Docker/AWS CLI 版本
2. ✅ 端口 8000 是否被占用
3. ✅ DynamoDB Local 日志 (`docker-compose logs`)
4. ✅ 表是否成功创建 (`aws dynamodb list-tables`)
5. ✅ 网络连接 (`ping localhost`)

祝你开发顺利！🚀
