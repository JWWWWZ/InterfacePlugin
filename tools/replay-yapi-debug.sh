#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEBUG_DIR="$REPO_ROOT/build/yapi-debug"

if [[ ! -d "$DEBUG_DIR" ]]; then
  echo "没有找到 YApi debug 目录：$DEBUG_DIR"
  exit 1
fi

PAYLOAD_FILE=""
if [[ $# -gt 0 ]]; then
  PAYLOAD_FILE="$1"
else
  PAYLOAD_FILE=$(find "$DEBUG_DIR" -maxdepth 1 -type f | sort | tail -n 1 || true)
fi

if [[ -z "$PAYLOAD_FILE" ]]; then
  echo "未找到任何 YApi payload 文件，请先运行插件生成调试文件。"
  exit 1
fi

if [[ ! -f "$PAYLOAD_FILE" ]]; then
  echo "指定的文件不存在：$PAYLOAD_FILE"
  exit 1
fi

if [[ -z "${YAPI_BASE_URL:-}" ]]; then
  echo "请先设置环境变量 YAPI_BASE_URL，例如："
  echo "  export YAPI_BASE_URL='http://localhost:3000'"
  exit 1
fi

if [[ -z "${YAPI_AUTH_TOKEN:-}" ]]; then
  echo "请先设置环境变量 YAPI_AUTH_TOKEN，例如："
  echo "  export YAPI_AUTH_TOKEN='your-token'"
  exit 1
fi

BASE_URL="${YAPI_BASE_URL%/}"
if [[ "$PAYLOAD_FILE" =~ yapi-change- ]]; then
  URL="$BASE_URL/api/interface/change"
elif [[ "$PAYLOAD_FILE" =~ yapi-add- ]]; then
  URL="$BASE_URL/api/interface/add"
else
  echo "无法从文件名判断要使用的 YApi 接口，请使用 yapi-add- 或 yapi-change- 前缀。"
  exit 1
fi

curl_cmd=(curl -v -X POST "$URL" -H "Content-Type: application/json")
if [[ "${YAPI_AUTH_TOKEN}" =~ ^Bearer[[:space:]] ]]; then
  curl_cmd+=( -H "Authorization: ${YAPI_AUTH_TOKEN}" )
else
  curl_cmd+=( -H "Cookie: yapi_token=${YAPI_AUTH_TOKEN}" )
fi
curl_cmd+=( --data-binary "@$PAYLOAD_FILE" )

echo "使用 payload 文件：$PAYLOAD_FILE"
echo "复现 curl 命令："
echo "${curl_cmd[@]}"
echo

"${curl_cmd[@]}"
