const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

async function resolveCheckedArtifact ({
  fileName,
  cacheDirectory,
  downloadUrl,
  expectedHash,
  hashAlgorithm,
  timeoutMilliseconds = 60000,
  fetchImpl = fetch
}) {
  const targetFile = path.join(cacheDirectory, fileName)
  const temporaryFile = `${targetFile}.part`
  fs.mkdirSync(cacheDirectory, { recursive: true })

  if (fs.existsSync(targetFile) && hash(targetFile, hashAlgorithm) === expectedHash.toLowerCase()) {
    return targetFile
  }

  fs.rmSync(targetFile, { force: true })
  fs.rmSync(temporaryFile, { force: true })
  try {
    const response = await fetchImpl(downloadUrl, {
      headers: { 'User-Agent': 'WorldManagement-player-E2E' },
      signal: AbortSignal.timeout(timeoutMilliseconds)
    })
    if (!response.ok) {
      throw new Error(`Could not download ${fileName}: HTTP ${response.status}.`)
    }
    fs.writeFileSync(temporaryFile, Buffer.from(await response.arrayBuffer()))
    const actualHash = hash(temporaryFile, hashAlgorithm)
    if (actualHash !== expectedHash.toLowerCase()) {
      const hashName = hashAlgorithm.toUpperCase().replace(/^SHA(\d+)$/, 'SHA-$1')
      throw new Error(`${fileName} ${hashName} mismatch: expected ${expectedHash}, got ${actualHash}.`)
    }
    fs.renameSync(temporaryFile, targetFile)
    return targetFile
  } catch (error) {
    fs.rmSync(temporaryFile, { force: true })
    fs.rmSync(targetFile, { force: true })
    throw error
  }
}

function hash (file, algorithm) {
  return crypto.createHash(algorithm).update(fs.readFileSync(file)).digest('hex')
}

module.exports = { resolveCheckedArtifact }