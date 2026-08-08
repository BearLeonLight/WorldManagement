const SHUTDOWN_FAILURES = ['zip file error', 'I/O shutdown failed', 'did not drain']
const RUNTIME_FAILURES = ['The server has not responded for ']
const SHUTDOWN_COMPLETE = 'WorldManagement terminal shutdown complete.'

function assertSuccessfulShutdown (log, label) {
  if (!log.includes(SHUTDOWN_COMPLETE)) {
    throw new Error(`${label} exited before WorldManagement terminal shutdown completed.`)
  }
  const failure = SHUTDOWN_FAILURES.find(marker => log.includes(marker))
  if (failure) throw new Error(`${label} shutdown reported '${failure}'.`)
  const runtimeFailure = RUNTIME_FAILURES.find(marker => log.includes(marker))
  if (runtimeFailure) throw new Error(`${label} runtime reported a Paper watchdog stall.`)
}

module.exports = { assertSuccessfulShutdown }