/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.sse

import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Options
import java.io.IOException

class ServerSentEventReader(
  private val source: BufferedSource,
  private val callback: Callback
) {
  private var lastId: String? = null

  interface Callback {
    fun onEvent(id: String?, type: String?, data: String)
    fun onRetryChange(timeMs: Long)
  }

  /**
   * Process the next event. This will result in a single call to [Callback.onEvent] *unless* the
   * data section was empty. Any number of calls to [Callback.onRetryChange] may occur while
   * processing an event.
   *
   * @return false when EOF is reached
   */
  @Throws(IOException::class)
  fun processNextEvent(): Boolean {
    var id = lastId
    var type: String? = null
    val data = Buffer()

    while (true) {
      val option = source.select(options)
      when (option) {
        0, 1, 2 -> {
          completeEvent(id, type, data)
          return true
        }

        3, 4, 5 -> {
          source.readData(data)
        }

        6, 7, 8 -> {
          id = source.readUtf8LineStrict().toNullIfEmpty()
        }

        9, 10, 11 -> {
          type = source.readUtf8LineStrict().toNullIfEmpty()
        }

        12, 13, 14 -> {
          val retryMs = source.readRetryMs()
          if (retryMs != -1L) {
            callback.onRetryChange(retryMs)
          }
        }

        -1 -> {
          val lineEnd = source.indexOfElement(CRLF)
          if (lineEnd != -1L) {
            // Skip the line and newline
            source.skip(lineEnd)
            source.select(options)
          } else {
            return false // No more newlines.
          }
        }

        else -> throw AssertionError()
      }
    }
  }

  @Throws(IOException::class)
  private fun completeEvent(id: String?, type: String?, data: Buffer) {
    if (data.size != 0L) {
      lastId = id
      data.skip(1L) // Leading newline.
      callback.onEvent(id, type, data.readUtf8())
    }
  }

  companion object {
    val options = Options.of(
        // 0, 1, 2
        "\r\n".encodeUtf8(),
        "\r".encodeUtf8(),
        "\n".encodeUtf8(),

        // 3, 4, 5
        "data: ".encodeUtf8(),
        "data:".encodeUtf8(),
        "data".encodeUtf8(),

        // 6, 7, 8
        "id: ".encodeUtf8(),
        "id:".encodeUtf8(),
        "id".encodeUtf8(),

        // 9, 10, 11
        "event: ".encodeUtf8(),
        "event:".encodeUtf8(),
        "event".encodeUtf8(),

        // 12, 13, 14
        "retry: ".encodeUtf8(),
        "retry:".encodeUtf8(),
        "retry".encodeUtf8()
    )

    private val CRLF = "\r\n".encodeUtf8()

    @Throws(IOException::class)
    private fun BufferedSource.readData(data: Buffer) {
      data.writeByte('\n'.toInt())
      readFully(data, indexOfElement(CRLF))
      select(options) // Skip the newline bytes.
    }

    @Throws(IOException::class)
    private fun BufferedSource.readRetryMs(): Long {
      val retryString = readUtf8LineStrict()
      return try {
        retryString.toLong()
      } catch (_: NumberFormatException) {
        -1L
      }
    }

    private fun String.toNullIfEmpty(): String? {
      if (isEmpty()) return null
      return this
    }
  }
}
