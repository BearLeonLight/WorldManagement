const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')
const { BUILD_ROOT, PROJECT_ROOT, resolveBuildChild } = require('./build-child-path.cjs')

test('defaults to the requested build child', () => {
  assert.equal(resolveBuildChild(undefined, 'console-command-test', 'WM_TEST_DIR'), path.join(BUILD_ROOT, 'console-command-test'))
})

test('accepts nested paths below build', () => {
  const nested = path.join(BUILD_ROOT, 'player-e2e', 'native')
  assert.equal(resolveBuildChild(nested, 'unused', 'WM_TEST_DIR'), nested)
})

test('rejects build itself', () => {
  assert.throws(() => resolveBuildChild(BUILD_ROOT, 'unused', 'WM_TEST_DIR'), /must be a child/)
})

test('rejects the project root', () => {
  assert.throws(() => resolveBuildChild(PROJECT_ROOT, 'unused', 'WM_TEST_DIR'), /must be a child/)
})

test('rejects paths outside build', () => {
  assert.throws(() => resolveBuildChild(path.join(PROJECT_ROOT, 'e2e'), 'unused', 'WM_TEST_DIR'), /must be a child/)
})
