#!/usr/bin/env bash
# §11.2 D1=B 自签路线：一次性生成本机根 CA，并用它签一张带域名/IP SAN 的服务端证书。
#
# 在哪跑：在一台"管理机"上跑，不在部署机上跑。跑完只把 certs/pushbox.crt、certs/pushbox.key、
# certs/pushbox_ca.crt 拷到部署机的 deploy/certs/；根 CA 私钥 pushbox_ca.key 离线保存、绝不进仓库或部署机。
#
# 用法：
#   PUSHBOX_HOST=pushbox.internal [PUSHBOX_IP=<部署机IP>] ./gen-ca.sh
# 叶子证书到期（默认 ~1 年）后，用同一根 CA 再跑一次本脚本即可只重签叶子，各端已信任的根不用动。

set -euo pipefail

host="${PUSHBOX_HOST:?需要 PUSHBOX_HOST，如 pushbox.internal}"
ip="${PUSHBOX_IP:-}"
ca_days="${CA_DAYS:-825}"      # 根 CA 有效期 ~2 年（§11.2 建议 1–2 年）
cert_days="${CERT_DAYS:-398}"  # 叶子证书有效期 ~1 年，到期用同一 CA 重签

here="$(cd "$(dirname "$0")" && pwd)"
certs="$here/../certs"
mkdir -p "$certs"

# SAN 至少要有服务端实际被访问的那个名字：域名、直连用的 IP、以及本机调试用的 localhost。
san="DNS:${host},DNS:localhost"
[ -n "$ip" ] && san="${san},IP:${ip}"

# 1) 根 CA（仅首次生成；已存在就复用，避免每次重签叶子都换根、逼所有端重新信任）
if [ ! -f "$certs/pushbox_ca.key" ]; then
  openssl genrsa -out "$certs/pushbox_ca.key" 4096
  openssl req -x509 -new -nodes -key "$certs/pushbox_ca.key" -sha256 -days "$ca_days" \
    -set_serial 1 -subj "/CN=PushBox Internal CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$certs/pushbox_ca.crt"
  echo "已生成根 CA -> $certs/pushbox_ca.crt"
  echo ">> 立刻把 pushbox_ca.key 移到离线介质并从本机删除；它不该出现在仓库或部署机上。"
fi

# 2) 服务端叶子证书（带 SAN，服务端认证看 SAN 不看 CN）
openssl genrsa -out "$certs/pushbox.key" 2048
openssl req -new -key "$certs/pushbox.key" -subj "/CN=${host}" -out "$certs/pushbox.csr"
openssl x509 -req -in "$certs/pushbox.csr" \
  -CA "$certs/pushbox_ca.crt" -CAkey "$certs/pushbox_ca.key" -CAcreateserial \
  -days "$cert_days" -sha256 \
  -extfile <(printf 'subjectAltName=%s\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nbasicConstraints=critical,CA:FALSE\n' "$san") \
  -out "$certs/pushbox.crt"
rm -f "$certs/pushbox.csr" "$certs"/*.srl

echo "服务端证书 -> $certs/pushbox.crt（SAN=${san}，${cert_days} 天）"
echo "分发根 CA（各端必须信任 pushbox_ca.crt，否则等于没加密）："
echo "  Android：放进 res/xml/network_security_config.xml 的 trust-anchors"
echo "  Windows：certutil -addstore -user Root pushbox_ca.crt"
echo "  第三方/Webhook：curl --cacert pushbox_ca.crt；Grafana/Alertmanager 配该根"
