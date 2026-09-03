.PHONY: up down test run package

# 本地常用命令统一入口（大厂惯例：新人 clone 后看一眼 Makefile/README 就能跑起来）
up:            ## 启动本地 MySQL + Redis
	docker compose up -d

down:          ## 停止本地依赖
	docker compose down

test:          ## 跑全部单测（秒级，不依赖容器）
	mvn -q test

run:           ## 启动应用（需先 make up）
	mvn spring-boot:run

package:       ## 打包（跳过测试的打包仅用于本地调试，CI 中禁止）
	mvn -q package -DskipTests
