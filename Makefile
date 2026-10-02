.PHONY: up down test red-contract run package demo negative

# 本地常用命令统一入口（大厂惯例：新人 clone 后看一眼 Makefile/README 就能跑起来）
up:            ## 启动本地 MySQL + Redis
	docker compose up -d

down:          ## 停止本地依赖
	docker compose down

test:          ## 跑全部单测（秒级，不依赖容器）
	mvn -q test

red-contract:  ## 跑 P2b 红灯合同（实施前大面积红=问题真实存在的证据；P2b 第 8 步应全绿）
	mvn -q test -Dred.contract=true -Dtest=P2bRedContractTest

run:           ## 启动应用（需先 make up）
	mvn spring-boot:run

package:       ## 打包（跳过测试的打包仅用于本地调试，CI 中禁止）
	mvn -q package -DskipTests

demo:          ## 开放接口全链路演示（需先 make up && make run）
	bash scripts/open-api/demo.sh

negative:      ## 开放接口反例演示：签名错/超窗/重放/缺token/越权/超配额（需先 make up && make run）
	bash scripts/open-api/negative.sh
