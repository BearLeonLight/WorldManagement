const path = require('node:path')

const PROJECT_ROOT = path.resolve(__dirname, '..')
const BUILD_ROOT = path.join(PROJECT_ROOT, 'build')

function resolveBuildChild (configuredPath, defaultChild, environmentName) {
  const resolved = path.resolve(configuredPath || path.join(BUILD_ROOT, defaultChild))
  const relative = path.relative(BUILD_ROOT, resolved)
  if (!relative || relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error(`${environmentName} must be a child of ${BUILD_ROOT}: ${resolved}`)
  }
  return resolved
}

module.exports = { BUILD_ROOT, PROJECT_ROOT, resolveBuildChild }
