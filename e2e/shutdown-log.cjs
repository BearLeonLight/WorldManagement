const SHUTDOWN_FAILURES = ['zip file error', 'I/O shutdown failed', 'did not drain']
const SHUTDOWN_COMPLETE = 'WorldManagement terminal shutdown complete.'

function assertSuccessfulShutdown (log, label) {
  if (!log.includes(SHUTDOWN_COMPLETE)) {
    throw new Error(`${label} exited before WorldManagement terminal shutdown completed.`)
  }
  const failure = SHUTDOWN_FAILURES.find(marker => log.includes(marker))
  if (failure) throw new Error(`${label} shutdown reported '${failure}'.`)
}

module.exports = { assertSuccessfulShutdown }