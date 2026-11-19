#!/usr/bin/env bash
# AURORA devices.json 签名脚本（服务器侧）。
#
# 用法: ./sign-devices.sh <devices.json 路径> [私钥 pem 路径]
# 输出: 一行 base64 签名（ECDSA P-256 / SHA256 / DER），放到 HTTP 响应头:
#   X-Aurora-Signature: <这行>
#
# 客户端(DevicePack.kt)用内嵌的 SPKI 公钥校验，缺头或校验失败 → 整包拒绝（fail-closed）。
set -euo pipefail

body="${1:?用法: sign-devices.sh <devices.json> [私钥.pem]}"
key="${2:-aurora_ec_private.pem}"

if [[ ! -f "$body" ]]; then echo "找不到 ${body}" >&2; exit 1; fi
if [[ ! -f "$key" ]]; then echo "找不到私钥 ${key}" >&2; exit 1; fi

openssl dgst -sha256 -sign "$key" -out /tmp/aurora_sig.der "$body"
openssl base64 -A -in /tmp/aurora_sig.der
echo
