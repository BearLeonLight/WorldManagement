const COMPATIBILITY_ERROR = /(?:no data available for version|unsupported protocol|protocol version|packet (?:decode|login)|read error for packet|partial packet|protodef)/i

function chooseConnectionMode (serverVersion, testedVersions, protocolForVersion, fallbackOverride) {
  if (!serverVersion || typeof serverVersion.name !== 'string' || !Number.isInteger(serverVersion.protocol)) {
    throw new TypeError('Paper status ping did not provide a valid version name and protocol.')
  }
  if (!Array.isArray(testedVersions) || testedVersions.length === 0) {
    throw new TypeError('Mineflayer did not provide any tested versions.')
  }

  const nativeVersion = testedVersions.includes(serverVersion.name)
    ? serverVersion.name
    : testedVersions.find(version => protocolForVersion(version) === serverVersion.protocol)
  const mode = nativeVersion ? 'native' : 'via-fallback'
  const fallbackClientVersion = fallbackOverride || testedVersions.at(-1)

  return {
    mode,
    clientVersion: nativeVersion || fallbackClientVersion,
    fallbackClientVersion,
    serverVersion: serverVersion.name,
    serverProtocol: serverVersion.protocol
  }
}

function shouldUseViaFallback (error) {
  return error?.phase === 'pre-spawn' && COMPATIBILITY_ERROR.test(String(error.message))
}

async function runWithViaFallback ({ decision, runNative, prepareVia, runVia }) {
  if (decision.mode === 'via-fallback') {
    const via = await prepareVia(decision)
    return runVia(decision, via)
  }

  try {
    return await runNative(decision)
  } catch (error) {
    if (!shouldUseViaFallback(error)) throw error
    const fallbackDecision = {
      ...decision,
      mode: 'via-fallback',
      clientVersion: decision.fallbackClientVersion
    }
    const via = await prepareVia(fallbackDecision)
    return runVia(fallbackDecision, via)
  }
}

module.exports = {
  chooseConnectionMode,
  runWithViaFallback,
  shouldUseViaFallback
}