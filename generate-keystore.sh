#!/bin/bash

# 生成 Android 签名密钥和 GitHub Secrets 所需信息的脚本

set -e

KEYSTORE_FILE="app/release.keystore"
PROPERTIES_FILE="keystore.properties"

echo "=== Android Release Keystore 生成工具 ==="
echo ""

# 检查 keytool 是否可用
if ! command -v keytool &> /dev/null; then
    echo "错误: 未找到 keytool，请确保已安装 JDK"
    exit 1
fi

# 交互式输入
read -rp "请输入密钥别名 (默认: mfca): " KEY_ALIAS
KEY_ALIAS=${KEY_ALIAS:-mfca}

read -rsp "请输入密钥库密码: " STORE_PASSWORD
echo ""
read -rsp "请确认密钥库密码: " STORE_PASSWORD_CONFIRM
echo ""

if [ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]; then
    echo "错误: 两次密码不一致"
    exit 1
fi

read -rsp "请输入密钥密码 (默认与密钥库密码相同): " KEY_PASSWORD
echo ""
KEY_PASSWORD=${KEY_PASSWORD:-$STORE_PASSWORD}

read -rp "请输入有效期 (天，默认: 10000): " VALIDITY
VALIDITY=${VALIDITY:-10000}

echo ""
echo "=== 填写证书信息 ==="
read -rp "您的姓名或组织 (CN，默认: mfca): " CN
CN=${CN:-mfca}
read -rp "组织单位 (OU，默认: mfca): " OU
OU=${OU:-mfca}
read -rp "组织 (O，默认: mfca): " O
O=${O:-mfca}
read -rp "城市 (L，默认: Beijing): " L
L=${L:-Beijing}
read -rp "省份 (ST，默认: Beijing): " ST
ST=${ST:-Beijing}
read -rp "国家代码 (C，默认: CN): " C
C=${C:-CN}

# 确保 app 目录存在
mkdir -p app

# 生成 keystore
echo ""
echo "正在生成密钥库..."
keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=$CN, OU=$OU, O=$O, L=$L, ST=$ST, C=$C"

# 生成 keystore.properties
cat > "$PROPERTIES_FILE" << EOF
STORE_FILE=release.keystore
STORE_PASSWORD=$STORE_PASSWORD
KEY_ALIAS=$KEY_ALIAS
KEY_PASSWORD=$KEY_PASSWORD
EOF

echo ""
echo "=== 生成完成 ==="
echo ""
echo "已生成文件:"
echo "  - $KEYSTORE_FILE"
echo "  - $PROPERTIES_FILE"
echo ""

# 输出 GitHub Secrets 信息
KEYSTORE_BASE64=$(base64 -w 0 "$KEYSTORE_FILE")

echo "=== GitHub Secrets 配置 ==="
echo ""
echo "请在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中添加以下 Secrets:"
echo ""
echo "KEYSTORE_BASE64:"
echo "  $KEYSTORE_BASE64"
echo ""
echo "KEYSTORE_PASSWORD:"
echo "  $STORE_PASSWORD"
echo ""
echo "KEY_ALIAS:"
echo "  $KEY_ALIAS"
echo ""
echo "KEY_PASSWORD:"
echo "  $KEY_PASSWORD"
echo ""
echo "注意: keystore.properties 和 release.keystore 已在 .gitignore 中排除，不会被提交到仓库。"

# 提示添加 .gitignore
if ! grep -q "release.keystore" .gitignore 2>/dev/null; then
    echo ""
    read -rp "是否将密钥文件添加到 .gitignore? (Y/n): " ADD_GITIGNORE
    ADD_GITIGNORE=${ADD_GITIGNORE:-Y}
    if [[ "$ADD_GITIGNORE" =~ ^[Yy]$ ]]; then
        echo "" >> .gitignore
        echo "# Signing" >> .gitignore
        echo "release.keystore" >> .gitignore
        echo "keystore.properties" >> .gitignore
        echo "已添加到 .gitignore"
    fi
fi
