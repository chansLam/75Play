// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.network

import java.io.IOException

/** Thrown when an existing Wi-Fi Direct connection must be reset before retrying. */
class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")
