#!/usr/bin/env node
/**
 * 清空所有活动设备记录，用于重置真机验收环境。
 * 用法: node clear-devices.mjs
 */

import { readFileSync } from 'fs';
import { execSync } from 'child_process';

// 读取环境变量
const envContent = readFileSync(new URL('../env/local.env', import.meta.url), 'utf-8');
const password = envContent.match(/PB_DB_PASSWORD='([^']+)'/)?.[1];

if (!password) {
  console.error('❌ 无法从 local.env 读取 PB_DB_PASSWORD');
  process.exit(1);
}

try {
  // 执行 MySQL 命令
  const sql = `DELETE FROM devices WHERE revoked_at IS NULL; SELECT COUNT(*) as remaining FROM devices;`;
  const cmd = `mysql -u pushbox -p'${password}' pushbox -e "${sql}"`;
  const output = execSync(cmd, { encoding: 'utf-8', stdio: 'pipe' });
  console.log('✅ 已清空所有活动设备记录');
  console.log(output);
} catch (err) {
  console.error('❌ 清理失败:', err.message);
  if (err.stderr) {
    console.error(err.stderr.toString());
  }
  process.exit(1);
}
