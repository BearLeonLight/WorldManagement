const test = require('node:test')
const assert = require('node:assert/strict')
const { assertCommandMatchesPath } = require('./runtime-coverage.cjs')

test('accepts canonical commands with argument placeholders', () => {
  assert.doesNotThrow(() => assertCommandMatchesPath('/wm create creative NORMAL FLAT 42', 'wm create <world> <environment> <world-type> <seed>'))
})

test('normalizes root aliases', () => {
  assert.doesNotThrow(() => assertCommandMatchesPath('worldmanager list', 'wm list'))
})

test('normalizes module aliases', () => {
  assert.doesNotThrow(() => assertCommandMatchesPath('wmwarp list world', 'wm warp list <world>'))
})

test('rejects the wrong literal', () => {
  assert.throws(() => assertCommandMatchesPath('/wm load world', 'wm unload <world>'), /expected literal 'unload'/)
})

test('rejects the wrong argument arity', () => {
  assert.throws(() => assertCommandMatchesPath('/wm tp self world 1 2', 'wm tp self <world> <x> <y> <z>'), /expected 7 tokens, got 6/)
})

test('accepts multiple tokens only for an explicit terminal greedy placeholder', () => {
  assert.doesNotThrow(() => assertCommandMatchesPath(
    '/wm display-name set creative <gradient:red:gold>Creative World</gradient>',
    'wm display-name set <world> <display-name...>'
  ))
  assert.throws(() => assertCommandMatchesPath(
    '/wm display-name reset creative extra',
    'wm display-name reset <world>'
  ), /expected 4 tokens, got 5/)
})

test('rejects a non-terminal greedy placeholder declaration', () => {
  assert.throws(() => assertCommandMatchesPath(
    '/wm broken some words confirm',
    'wm broken <value...> confirm'
  ), /terminal/)
})
