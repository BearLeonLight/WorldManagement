const fs = require('node:fs')
const path = require('node:path')

const MANIFEST_PATH = path.resolve(__dirname, 'command-runtime-coverage.txt')
const ALIASES = new Map([
  ['worldmanager', ['wm']],
  ['wmwarp', ['wm', 'warp']],
  ['wmown', ['wm', 'ownership']],
  ['wmstore', ['wm', 'storage']]
])

function expectedRuntimeCoverage (level) {
  const entries = fs.readFileSync(MANIFEST_PATH, 'utf8')
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#'))
    .map(line => {
      const separator = line.indexOf(' ')
      if (separator < 1) throw new Error(`Invalid runtime coverage entry: ${line}`)
      return { level: line.slice(0, separator), path: line.slice(separator + 1) }
    })
  const duplicatePaths = entries
    .map(entry => entry.path)
    .filter((entryPath, index, paths) => paths.indexOf(entryPath) !== index)
  if (duplicatePaths.length > 0) {
    throw new Error(`Duplicate command runtime coverage paths: ${[...new Set(duplicatePaths)].join(', ')}`)
  }
  return new Set(entries.filter(entry => entry.level === level).map(entry => entry.path))
}

function assertRuntimeCoverage (level, coveredPaths) {
  const expected = expectedRuntimeCoverage(level)
  const covered = new Set(coveredPaths)
  const missing = [...expected].filter(commandPath => !covered.has(commandPath))
  const unexpected = [...covered].filter(commandPath => !expected.has(commandPath))
  if (missing.length > 0 || unexpected.length > 0) {
    throw new Error([
      `${level} command coverage mismatch.`,
      missing.length > 0 ? `Missing: ${missing.join(', ')}` : '',
      unexpected.length > 0 ? `Unexpected: ${unexpected.join(', ')}` : ''
    ].filter(Boolean).join(' '))
  }
  return covered.size
}

function assertCommandMatchesPath (input, commandPath) {
  const inputTokens = input.trim().replace(/^\//, '').split(/\s+/)
  const alias = ALIASES.get(inputTokens[0])
  const canonicalTokens = alias ? [...alias, ...inputTokens.slice(1)] : inputTokens
  const pathTokens = commandPath.split(/\s+/)
  const greedyIndexes = pathTokens
    .map((token, index) => token.startsWith('<') && (token.endsWith('...>') || token === '<options>') ? index : -1)
    .filter(index => index >= 0)
  if (greedyIndexes.some(index => index !== pathTokens.length - 1) || greedyIndexes.length > 1) {
    throw new Error(`Command path '${commandPath}' may declare at most one terminal greedy placeholder.`)
  }
  const greedy = greedyIndexes.length === 1
  if ((!greedy && canonicalTokens.length !== pathTokens.length) ||
      (greedy && canonicalTokens.length < pathTokens.length)) {
    throw new Error(`Command '${input}' does not match '${commandPath}': expected ${pathTokens.length} tokens, got ${canonicalTokens.length}.`)
  }
  for (let index = 0; index < pathTokens.length; index++) {
    const expected = pathTokens[index]
    if (greedy && index === pathTokens.length - 1) return
    if (!expected.startsWith('<') && canonicalTokens[index] !== expected) {
      throw new Error(`Command '${input}' does not match '${commandPath}': expected literal '${expected}' at token ${index + 1}.`)
    }
  }
}

module.exports = { assertCommandMatchesPath, assertRuntimeCoverage, expectedRuntimeCoverage }
