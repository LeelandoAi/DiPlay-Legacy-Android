const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const root = process.cwd();
const buildGradle = fs.readFileSync(path.join(root, 'mobile', 'build.gradle.kts'), 'utf8');
const vname = buildGradle.match(/versionName\s*=\s*"([^"]+)"/)[1];
const vcode = buildGradle.match(/versionCode\s*=\s*(\d+)/)[1];
const headCommit = cp.execSync('git rev-parse HEAD', { encoding: 'utf8' }).trim();

console.log('==> Verifying Release APK vs Current Source');
console.log('    Target Version:', vname, `(code ${vcode})`);
console.log('    Current HEAD:  ', headCommit);

const apkName = `DiPlay-v${vname}-legacy-release.apk`;
const apkPath = path.join(root, apkName);

if (!fs.existsSync(apkPath)) {
  console.error(`ERROR: Release APK not found at ${apkPath}`);
  process.exit(1);
}

// 1. Check embedded version-control-info.textproto
let proto = '';
try {
  proto = cp.execSync(`tar -xOf "${apkName}" META-INF/version-control-info.textproto`, { encoding: 'utf8' });
} catch (e) {
  console.error('ERROR: Could not read META-INF/version-control-info.textproto from APK:', e.message);
  process.exit(1);
}

const revMatch = proto.match(/revision:\s*"([a-f0-9]+)"/);
if (!revMatch) {
  console.error('ERROR: Could not find git revision inside APK version-control-info.textproto');
  process.exit(1);
}
const apkCommit = revMatch[1];
console.log('    APK Commit:    ', apkCommit);

if (apkCommit === headCommit) {
  console.log('    Commit Check:   EXACT MATCH (100% synchronized)');
} else {
  const diff = cp.execSync(`git diff ${apkCommit} ${headCommit} -- mobile/ shared/ common/ home/`, { encoding: 'utf8' }).trim();
  if (diff.length === 0) {
    console.log(`    Commit Check:   VALID (APK built from ${apkCommit.slice(0, 7)}, 0 code changes to HEAD ${headCommit.slice(0, 7)})`);
  } else {
    console.error('    Commit Check:   FAILED! Code differences detected between APK and HEAD:');
    console.error(diff.slice(0, 1000));
    process.exit(1);
  }
}

// 2. Check SHA256 checksum
const shaFile = `${apkPath}.sha256`;
if (fs.existsSync(shaFile)) {
  const crypto = require('crypto');
  const actualHash = crypto.createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex').toLowerCase();
  const expectedHash = fs.readFileSync(shaFile, 'utf8').trim().split(/\s+/)[0].toLowerCase();
  if (actualHash !== expectedHash) {
    console.error(`ERROR: SHA256 mismatch! Actual: ${actualHash}, Recorded in file: ${expectedHash}`);
    process.exit(1);
  }
  console.log('    SHA256 Match:  ', actualHash);
}

console.log('==> SUCCESS: Release APK strictly matches current source code.\n');
