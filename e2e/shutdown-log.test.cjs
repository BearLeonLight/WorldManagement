const assert = require('node:assert/strict')
const test = require('node:test')

const { assertSuccessfulShutdown } = require('./shutdown-log.cjs')

test('accepts one attempt with a terminal shutdown marker', () => {
  assert.doesNotThrow(() => assertSuccessfulShutdown(
    '[WorldManagement] WorldManagement terminal shutdown complete.\n',
    'first Paper'
  ))
})

test('rejects one attempt without a terminal shutdown marker', () => {
  assert.throws(
    () => assertSuccessfulShutdown('[WorldManagement] Disabling WorldManagement\n', 'first Paper'),
    /first Paper exited before WorldManagement terminal shutdown completed/
  )
})

test('rejects one attempt that reports a drain failure', () => {
  assert.throws(
    () => assertSuccessfulShutdown(
      '[WorldManagement] Lifecycle operations did not drain before shutdown deadline.\n' +
        '[WorldManagement] WorldManagement terminal shutdown complete.\n',
      'first Paper'
    ),
    /first Paper shutdown reported 'did not drain'/
  )
})