package io.github.nexalloy.revanced.shared.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.BitSet

internal object Mp4Join {
    private const val MAX_HEADER_BOX = 16 * 1024 * 1024
    private const val MAX_SAMPLES = 2_000_000
    private const val CHUNK_SECONDS = 0.5
    private const val MOVIE_TIMESCALE = 1000L
    private const val SAMPLE_IS_NON_SYNC = 0x00010000

    private fun type(name: String) = (name[0].code shl 24) or (name[1].code shl 16) or (name[2].code shl 8) or name[3].code

    private val FTYP = type("ftyp"); private val MOOV = type("moov"); private val MOOF = type("moof"); private val MDAT = type("mdat")
    private val MVHD = type("mvhd"); private val TRAK = type("trak"); private val TKHD = type("tkhd"); private val EDTS = type("edts")
    private val ELST = type("elst"); private val MDIA = type("mdia"); private val MDHD = type("mdhd"); private val HDLR = type("hdlr")
    private val MINF = type("minf"); private val STBL = type("stbl"); private val STSD = type("stsd"); private val STSZ = type("stsz")
    private val STTS = type("stts"); private val CTTS = type("ctts"); private val STSS = type("stss"); private val STSC = type("stsc")
    private val STCO = type("stco"); private val CO64 = type("co64"); private val MVEX = type("mvex"); private val TREX = type("trex")
    private val TRAF = type("traf"); private val TFHD = type("tfhd"); private val TFDT = type("tfdt"); private val TRUN = type("trun")
    private val VMHD = type("vmhd"); private val SMHD = type("smhd"); private val NMHD = type("nmhd"); private val DINF = type("dinf")
    private val DREF = type("dref"); private val URL = type("url "); val VIDE = type("vide"); val SOUN = type("soun")
    private val ENCV = type("encv"); private val ENCA = type("enca")

    private val IDENTITY = intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    fun join(video: File, audio: File?, out: File) {
        RandomAccessFile(video, "r").use { videoIn ->
            val audioIn = audio?.let { RandomAccessFile(it, "r") }
            try {
                val tracks = ArrayList<Track>()
                tracks += Track.read(videoIn, VIDE, "video", "picture")
                if (audioIn != null) tracks += Track.read(audioIn, SOUN, "audio", "sound")
                write(tracks, out)
            } finally {
                audioIn?.close()
            }
        }
    }

    private class Track(val file: RandomAccessFile, val handler: Int, val kind: String) {
        var trackId = 0L
        var timescale = 0L
        var language = 0
        var movieTimescale = 0L
        var descriptions: ByteBuffer? = null
        var descriptionCount = 0
        var matrix: ByteArray? = null
        var width = 0
        var height = 0
        var volume = 0
        var edits: Array<LongArray>? = null

        var defaultDescription = 1
        var defaultDuration = 0
        var defaultSize = 0
        var defaultFlags = 0

        val sizes = Ints()
        val durations = Ints()
        val offsets = Ints()
        val nonSync = BitSet()
        var runStarts = LongArray(16)
        val runEnds = Ints()
        val runDescriptions = Ints()
        var mediaStarts = LongArray(16)
        var mediaEnds = LongArray(16)
        var mediaBoxes = 0

        var firstDecodeTime = -1L
        var decodeTime = 0L
        private var listedTables: List<Box>? = null
        val chunks = ArrayList<Chunk>()

        fun count() = sizes.size

        companion object {
            fun read(file: RandomAccessFile, handler: Int, kind: String, what: String): Track {
                val track = Track(file, handler, kind)
                try {
                    track.readBoxes(what)
                } catch (e: BufferUnderflowException) {
                    throw IOException("a box of the $kind file is cut short", e)
                } catch (e: IndexOutOfBoundsException) {
                    throw IOException("a box of the $kind file is cut short", e)
                } catch (e: IllegalArgumentException) {
                    throw IOException("a box of the $kind file is cut short", e)
                }
                return track
            }
        }

        private fun readBoxes(what: String) {
            val length = file.length()
            var at = 0L
            var described = false
            val head = ByteArray(16)
            while (length - at >= 8) {
                file.seek(at)
                file.readFully(head, 0, 8)
                var size = ByteBuffer.wrap(head).getInt(0).toLong() and 0xFFFFFFFFL
                val type = ByteBuffer.wrap(head).getInt(4)
                var header = 8
                if (size == 1L) {
                    if (length - at < 16) throw IOException("a box runs past the end of the $kind file")
                    file.readFully(head, 8, 8)
                    size = ByteBuffer.wrap(head).getLong(8)
                    header = 16
                } else if (size == 0L) {
                    size = length - at
                }
                if (size < header || size > length - at) throw IOException("a box runs past the end of the $kind file")
                when (type) {
                    MOOV -> {
                        if (described) throw IOException("the $kind file has two moov boxes")
                        describe(whole(at + header, size - header), what)
                        described = true
                    }
                    MOOF -> {
                        if (!described) throw IOException("the $kind file has a fragment before its moov")
                        fragment(whole(at + header, size - header), at, length)
                    }
                    MDAT -> {
                        if (mediaBoxes == MAX_SAMPLES) throw IOException("the $kind file lists too many media data boxes")
                        if (mediaBoxes == mediaStarts.size) {
                            val capacity = minOf(MAX_SAMPLES, mediaBoxes * 2)
                            mediaStarts = mediaStarts.copyOf(capacity)
                            mediaEnds = mediaEnds.copyOf(capacity)
                        }
                        mediaStarts[mediaBoxes] = at + header
                        mediaEnds[mediaBoxes++] = at + size
                    }
                }
                at += size
            }
            if (!described) throw IOException("the $kind file has no moov")
            if (sizes.size == 0) throw IOException("the $kind file holds no samples")
            validateMediaData()
        }

        private fun validateMediaData() {
            if (mediaBoxes == 0) throw IOException("the $kind file has no media data boxes")
            var first = 0
            for (run in 0 until runEnds.size) {
                var bytes = 0L
                val end = runEnds[run]
                for (sample in first until end) bytes += sizes[sample]
                first = end
                if (bytes == 0L) continue
                val start = runStarts[run]
                var box = java.util.Arrays.binarySearch(mediaStarts, 0, mediaBoxes, start)
                if (box < 0) box = -box - 2
                if (box < 0 || bytes > mediaEnds[box] - start) throw IOException("a fragment's samples lie outside the $kind file's media data")
            }
        }

        private fun whole(at: Long, size: Long): ByteBuffer {
            if (size > MAX_HEADER_BOX) throw IOException("the $kind file has a header box of $size bytes")
            val bytes = ByteArray(size.toInt())
            file.seek(at)
            file.readFully(bytes)
            return ByteBuffer.wrap(bytes)
        }

        private fun describe(moov: ByteBuffer, what: String) {
            var mvex: ByteBuffer? = null
            for (box in children(moov)) {
                when {
                    box.type == MVHD -> {
                        val b = box.data
                        b.position(if (b.get(0).toInt() == 1) 20 else 12)
                        movieTimescale = u32(b)
                    }
                    box.type == TRAK && trackId == 0L -> trak(box.data)
                    box.type == MVEX -> mvex = box.data
                }
            }
            if (trackId == 0L) throw IOException("the $kind file holds no $what track")
            if (timescale == 0L) throw IOException("the $kind track has no timescale")
            if (movieTimescale == 0L) movieTimescale = timescale
            listedTables?.let {
                if (mvex != null) throw IOException("the $kind track keeps samples outside its fragments")
                plain(it)
                return
            }
            val ex = mvex ?: return
            for (box in children(ex)) {
                if (box.type != TREX) continue
                val b = box.data
                b.position(4)
                if (u32(b) != trackId) continue
                defaultDescription = b.int
                defaultDuration = b.int
                defaultSize = b.int
                defaultFlags = b.int
            }
        }

        private fun trak(trak: ByteBuffer) {
            val parts = children(trak)
            val mdia = find(parts, MDIA) ?: return
            val media = children(mdia.data)
            val hdlr = find(media, HDLR)
            if (hdlr == null || hdlr.data.getInt(8) != handler) return

            val tkhd = find(parts, TKHD) ?: throw IOException("the $kind track has no header")
            val b = tkhd.data
            val wide = b.get(0).toInt() == 1
            b.position(if (wide) 20 else 12)
            trackId = u32(b)
            if (trackId == 0L) throw IOException("the $kind track has no id")
            b.position(b.position() + 4 + (if (wide) 8 else 4) + 12)
            volume = b.short.toInt()
            b.position(b.position() + 2)
            matrix = ByteArray(36).also { b.get(it) }
            width = b.int
            height = b.int

            val edts = find(parts, EDTS)
            val elst = edts?.let { find(children(it.data), ELST) }
            if (elst != null) {
                val e = elst.data
                val wideEdits = e.get(0).toInt() == 1
                e.position(4)
                val count = u32(e)
                if (count > e.remaining() / 12) throw IOException("the $kind track's edit list is cut short")
                edits = Array(count.toInt()) {
                    val duration = if (wideEdits) e.long else u32(e)
                    val mediaTime = if (wideEdits) e.long else e.int.toLong()
                    longArrayOf(duration, mediaTime, e.int.toLong())
                }
            }

            val mdhd = find(media, MDHD) ?: throw IOException("the $kind track has no media header")
            val m = mdhd.data
            val wideMedia = m.get(0).toInt() == 1
            m.position(if (wideMedia) 20 else 12)
            timescale = u32(m)
            m.position(m.position() + if (wideMedia) 8 else 4)
            language = m.short.toInt() and 0x7FFF

            val minf = find(media, MINF)
            val stbl = minf?.let { find(children(it.data), STBL) } ?: throw IOException("the $kind track has no sample table")
            val tables = children(stbl.data)
            val stsd = find(tables, STSD) ?: throw IOException("the $kind track has no sample description")
            descriptionCount = stsd.data.getInt(4)
            val entries = children(slice(stsd.data, 8, stsd.data.limit()))
            if (descriptionCount < 1 || entries.size < descriptionCount) throw IOException("the $kind track's sample descriptions are cut short")
            if (entries.any { it.type == ENCV || it.type == ENCA }) throw IOException("the $kind track is encrypted")
            var desc = slice(stsd.data, 0, 8 + entries[descriptionCount - 1].end)
            val lastStart = 8 + if (descriptionCount == 1) 0 else entries[descriptionCount - 2].end
            if (desc.getInt(lastStart) == 0) {
                val copy = ByteArray(desc.limit())
                desc.duplicate().get(copy)
                desc = ByteBuffer.wrap(copy).putInt(lastStart, copy.size - lastStart)
            }
            descriptions = desc
            val stsz = find(tables, STSZ)
            val stts = find(tables, STTS)
            if ((stsz != null && stsz.data.getInt(8) != 0) || (stts != null && stts.data.getInt(4) != 0)) listedTables = tables
        }

        private fun plain(tables: List<Box>) {
            val stsz = find(tables, STSZ)
            val stts = find(tables, STTS)
            val stsc = find(tables, STSC)
            val stco = find(tables, STCO)
            val co64 = find(tables, CO64)
            if (stsz == null || stts == null || stsc == null || (stco == null && co64 == null)) {
                throw IOException("the $kind track's sample tables are incomplete")
            }
            val z = stsz.data
            z.position(4)
            val fixed = u32(z)
            val count = u32(z)
            if (count > MAX_SAMPLES) throw IOException("the $kind file lists too many samples")
            if (fixed == 0L && count > z.remaining() / 4) throw cutShort("size")
            for (i in 0 until count) {
                val size = if (fixed != 0L) fixed else u32(z)
                if (size > Int.MAX_VALUE) throw IOException("a sample of the $kind file is over 2 GB")
                sizes.add(size.toInt())
            }

            val t = stts.data
            t.position(4)
            var entries = u32(t)
            if (entries > t.remaining() / 8) throw cutShort("time")
            for (e in 0 until entries) {
                val samples = u32(t)
                val duration = t.int
                if (samples > count - durations.size) throw disagree()
                for (i in 0 until samples) {
                    durations.add(duration)
                    decodeTime += duration.toLong() and 0xFFFFFFFFL
                }
            }
            if (durations.size.toLong() != count) throw disagree()
            firstDecodeTime = 0

            find(tables, CTTS)?.let { ctts ->
                val c = ctts.data
                c.position(4)
                entries = u32(c)
                if (entries > c.remaining() / 8) throw cutShort("composition offset")
                for (e in 0 until entries) {
                    val samples = u32(c)
                    val offset = c.int
                    if (samples > count - offsets.size) throw disagree()
                    for (i in 0 until samples) offsets.add(offset)
                }
            }
            while (offsets.size < count) offsets.add(0)

            find(tables, STSS)?.let { stss ->
                val s = stss.data
                s.position(4)
                entries = u32(s)
                if (entries > s.remaining() / 4) throw cutShort("sync sample")
                nonSync.set(0, count.toInt())
                for (e in 0 until entries) {
                    val sample = u32(s)
                    if (sample < 1 || sample > count) throw disagree()
                    nonSync.clear(sample.toInt() - 1)
                }
            }

            val wide = co64 != null
            val o = (co64 ?: stco)!!.data
            val chunkCount = o.getInt(4).toLong() and 0xFFFFFFFFL
            if (chunkCount > MAX_SAMPLES || chunkCount > (o.limit() - 8) / (if (wide) 8 else 4)) throw cutShort("chunk offset")
            val c = stsc.data
            entries = c.getInt(4).toLong() and 0xFFFFFFFFL
            if (entries > (c.limit() - 8) / 12) throw cutShort("sample-to-chunk")
            var sample = 0
            for (r in 0 until entries.toInt()) {
                val rule = 8 + r * 12
                val first = c.getInt(rule).toLong() and 0xFFFFFFFFL
                val perChunk = c.getInt(rule + 4).toLong() and 0xFFFFFFFFL
                val description = c.getInt(rule + 8).toLong() and 0xFFFFFFFFL
                val last = if (r + 1 < entries) (c.getInt(rule + 12).toLong() and 0xFFFFFFFFL) - 1 else chunkCount
                if (first < 1 || last < first || last > chunkCount) throw disagree()
                if (description < 1 || description > descriptionCount) throw IOException("a chunk of the $kind file names a sample description it hasn't got")
                for (chunk in first..last) {
                    if (perChunk > count - sample) throw disagree()
                    if (perChunk == 0L) continue
                    val at = (8 + (chunk - 1) * (if (wide) 8 else 4)).toInt()
                    val start = if (wide) o.getLong(at) else o.getInt(at).toLong() and 0xFFFFFFFFL
                    if (start < 0) throw IOException("a chunk of the $kind file has an unsupported data offset")
                    sample += perChunk.toInt()
                    addRun(start, sample, description.toInt())
                }
            }
            if (sample.toLong() != count) throw disagree()
        }

        private fun cutShort(table: String) = IOException("the $kind track's $table table is cut short")
        private fun disagree() = IOException("the $kind track's sample tables disagree on its samples")

        private fun addRun(start: Long, end: Int, description: Int) {
            val runs = runEnds.size
            if (runs == runStarts.size) runStarts = runStarts.copyOf(runs * 2)
            runStarts[runs] = start
            runEnds.add(end)
            runDescriptions.add(description)
        }

        private fun fragment(moof: ByteBuffer, moofStart: Long, length: Long) {
            var firstTraf = true
            for (traf in children(moof)) {
                if (traf.type != TRAF) continue
                val first = firstTraf
                firstTraf = false
                val parts = children(traf.data)
                val tfhd = find(parts, TFHD) ?: throw IOException("a fragment of the $kind file has no header")
                val h = tfhd.data
                val flags = h.int and 0xFFFFFF
                if (u32(h) != trackId) continue
                val base = when {
                    flags and 0x1 != 0 -> h.long
                    flags and 0x020000 != 0 || first -> moofStart
                    else -> throw IOException("a fragment of the $kind file puts its data after another track's")
                }
                if (base < 0) throw IOException("a fragment of the $kind file has an unsupported data offset")
                val description = if (flags and 0x2 != 0) h.int else defaultDescription
                val duration = if (flags and 0x8 != 0) h.int else defaultDuration
                val size = if (flags and 0x10 != 0) h.int else defaultSize
                val sampleFlags = if (flags and 0x20 != 0) h.int else defaultFlags
                if (description < 1 || description > descriptionCount) throw IOException("a fragment of the $kind file names a sample description it hasn't got")

                val tfdt = find(parts, TFDT)
                if (tfdt != null) {
                    val t = tfdt.data
                    val wide = t.get(0).toInt() == 1
                    t.position(4)
                    startAt(if (wide) t.long else u32(t))
                } else if (firstDecodeTime < 0) {
                    startAt(0)
                }
                var next = base
                for (trun in parts) {
                    if (trun.type == TRUN) next = run(trun.data, base, next, description, duration, size, sampleFlags, length)
                }
            }
        }

        private fun run(r: ByteBuffer, base: Long, next: Long, description: Int, duration: Int, size: Int, sampleFlags: Int, length: Long): Long {
            val flags = r.int and 0xFFFFFF
            val count = u32(r)
            var start = next
            if (flags and 0x1 != 0) {
                start = try { Math.addExact(base, r.int.toLong()) } catch (e: ArithmeticException) {
                    throw IOException("a fragment's data offset overflows in the $kind file", e)
                }
            }
            val hasFirstFlags = flags and 0x4 != 0
            val firstFlags = if (hasFirstFlags) r.int else 0
            val fields = Integer.bitCount(flags and 0xF00)
            if (fields > 0 && count > r.remaining() / (4L * fields)) throw IOException("a run of the $kind file lists more samples than it holds")
            if (sizes.size + count > MAX_SAMPLES) throw IOException("the $kind file lists too many samples")
            if (count == 0L) return start
            var bytes = 0L
            for (i in 0 until count) {
                val sampleDuration = if (flags and 0x100 != 0) r.int else duration
                val sampleSize = if (flags and 0x200 != 0) r.int else size
                val flagsOf = if (flags and 0x400 != 0) r.int else if (i == 0L && hasFirstFlags) firstFlags else sampleFlags
                val offset = if (flags and 0x800 != 0) r.int else 0
                if (sampleSize < 0) throw IOException("a sample of the $kind file is over 2 GB")
                if (flagsOf and SAMPLE_IS_NON_SYNC != 0) nonSync.set(sizes.size)
                sizes.add(sampleSize)
                durations.add(sampleDuration)
                offsets.add(offset)
                decodeTime += sampleDuration.toLong() and 0xFFFFFFFFL
                bytes += sampleSize
            }
            if (start < 0 || start > length || bytes > length - start) throw IOException("a fragment's samples run past the end of the $kind file")
            addRun(start, sizes.size, description)
            return start + bytes
        }

        private fun startAt(time: Long) {
            if (firstDecodeTime < 0 || durations.size == 0) {
                firstDecodeTime = time
                decodeTime = time
                return
            }
            if (time == decodeTime) return
            val last = durations.size - 1
            val lastDuration = durations[last].toLong() and 0xFFFFFFFFL
            val moved = maxOf(0L, minOf(0xFFFFFFFFL, lastDuration + time - decodeTime))
            durations[last] = moved.toInt()
            decodeTime += moved - lastDuration
        }
    }

    private class Timing(track: Track) {
        val shift: Long
        val mediaDuration: Long
        val edits: Array<LongArray>?
        val duration: Long

        init {
            var lowest = 0L
            for (i in 0 until track.count()) lowest = minOf(lowest, track.offsets[i].toLong())
            shift = -lowest
            var end = 0L
            var decode = 0L
            for (i in 0 until track.count()) {
                val length = track.durations[i].toLong() and 0xFFFFFFFFL
                end = maxOf(end, decode + track.offsets[i] + shift + length)
                decode += length
            }
            mediaDuration = decode

            val input = track.edits?.takeIf { it.isNotEmpty() } ?: arrayOf(longArrayOf(0, 0, 0x00010000))
            val output = ArrayList<LongArray>()
            for (edit in input) {
                val segment = edit[0]
                if (edit[1] == -1L) {
                    if (segment > 0) output += longArrayOf(scale(segment, track.movieTimescale), -1, 0x00010000)
                    continue
                }
                var media = edit[1] - track.firstDecodeTime + shift
                var shown = if (segment == 0L) -1 else scale(segment, track.movieTimescale)
                if (media < 0) {
                    val lead = scale(-media, track.timescale)
                    output += longArrayOf(lead, -1, 0x00010000)
                    if (shown >= 0) shown = maxOf(0, shown - lead)
                    media = 0
                }
                val available = scale(maxOf(0, end - media), track.timescale)
                shown = if (shown < 0) available else minOf(shown, available)
                if (shown > 0) output += longArrayOf(shown, media, edit[2])
            }
            if (output.isEmpty()) throw IOException("the ${track.kind} track's edit list shows nothing")
            val total = output.sumOf { it[0] }
            val only = output[0]
            val identity = output.size == 1 && only[1] == 0L && only[2] == 0x00010000L && only[0] == scale(end, track.timescale)
            edits = if (identity) null else output.toTypedArray()
            duration = if (identity) scale(mediaDuration, track.timescale) else total
        }

        companion object {
            fun scale(value: Long, timescale: Long) =
                value / timescale * MOVIE_TIMESCALE + (value % timescale * MOVIE_TIMESCALE + timescale / 2) / timescale
        }
    }

    private class Chunk(val track: Track, val first: Int, val count: Int, val from: Long, val bytes: Long, val description: Int) {
        var at = 0L
    }

    private fun write(tracks: List<Track>, out: File) {
        val chunks = interleave(tracks)
        var data = 0L
        for (chunk in chunks) {
            chunk.at = data
            data += chunk.bytes
        }
        val timing = Array(tracks.size) { Timing(tracks[it]) }
        val ftyp = ftyp()
        val wide = data + (64L shl 20) > 0xFFFFFFFFL
        val mdatHeader = if (data + 8 > 0xFFFFFFFFL) 16 else 8
        val dataStart = ftyp.size + moov(tracks, timing, 0, wide).size + mdatHeader
        val moov = moov(tracks, timing, dataStart.toLong(), wide)
        FileOutputStream(out).use { stream ->
            val channel = stream.channel
            writeFully(channel, ByteBuffer.wrap(ftyp))
            writeFully(channel, ByteBuffer.wrap(moov))
            val header = ByteBuffer.allocate(mdatHeader)
            if (mdatHeader == 16) header.putInt(1).putInt(MDAT).putLong(data + 16) else header.putInt((data + 8).toInt()).putInt(MDAT)
            header.flip()
            writeFully(channel, header)
            for (chunk in chunks) {
                val from = chunk.track.file.channel
                var done = 0L
                while (done < chunk.bytes) {
                    val moved = from.transferTo(chunk.from + done, chunk.bytes - done, channel)
                    if (moved <= 0) throw IOException("the ${chunk.track.kind} file ended before its samples did")
                    done += moved
                }
            }
        }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    private fun interleave(tracks: List<Track>): List<Chunk> {
        val n = tracks.size
        val next = IntArray(n)
        val run = IntArray(n)
        val within = LongArray(n)
        val time = LongArray(n)
        val chunks = ArrayList<Chunk>()
        while (true) {
            var pick = -1
            var earliest = 0.0
            for (t in 0 until n) {
                val track = tracks[t]
                if (next[t] >= track.count()) continue
                val at = (track.firstDecodeTime + time[t]) / track.timescale.toDouble()
                if (pick < 0 || at < earliest) { pick = t; earliest = at }
            }
            if (pick < 0) return chunks
            val track = tracks[pick]
            val first = next[pick]
            val end = track.runEnds[run[pick]]
            val limit = time[pick] + (CHUNK_SECONDS * track.timescale).toLong()
            var bytes = 0L
            var i = first
            while (i < end && (i == first || time[pick] < limit)) {
                bytes += track.sizes[i]
                time[pick] += track.durations[i].toLong() and 0xFFFFFFFFL
                i++
            }
            val chunk = Chunk(track, first, i - first, track.runStarts[run[pick]] + within[pick], bytes, track.runDescriptions[run[pick]])
            chunks += chunk
            track.chunks += chunk
            next[pick] = i
            if (i == end) { run[pick]++; within[pick] = 0 } else within[pick] += bytes
        }
    }

    private fun ftyp(): ByteArray = Boxes().apply {
        begin(FTYP).u32(type("isom").toLong()).u32(0x200).u32(type("isom").toLong()).u32(type("iso2").toLong()).u32(type("mp41").toLong()).end()
    }.bytes()

    private fun moov(tracks: List<Track>, timing: Array<Timing>, dataStart: Long, wide: Boolean): ByteArray {
        val duration = timing.maxOf { it.duration }
        val out = Boxes()
        out.begin(MOOV)
        val wideMovie = duration > 0xFFFFFFFFL
        out.full(MVHD, if (wideMovie) 1 else 0, 0)
        times(out, wideMovie).u32(MOVIE_TIMESCALE)
        length(out, wideMovie, duration)
        out.u32(0x00010000).u16(0x0100).zeros(10)
        IDENTITY.forEach { out.u32(it.toLong()) }
        out.zeros(24).u32((tracks.size + 1).toLong()).end()
        tracks.forEachIndexed { t, track -> trak(out, track, timing[t], t + 1, dataStart, wide) }
        return out.end().bytes()
    }

    private fun trak(out: Boxes, track: Track, timing: Timing, id: Int, dataStart: Long, wide: Boolean) {
        out.begin(TRAK)
        val wideTrack = timing.duration > 0xFFFFFFFFL
        out.full(TKHD, if (wideTrack) 1 else 0, 0x3)
        times(out, wideTrack).u32(id.toLong()).u32(0)
        length(out, wideTrack, timing.duration)
        out.zeros(8).u16(0).u16(0).u16(if (track.handler == SOUN) 0x0100 else 0).u16(0)
        val matrix = track.matrix
        if (matrix != null) out.bytes(matrix) else IDENTITY.forEach { out.u32(it.toLong()) }
        out.u32(track.width.toLong() and 0xFFFFFFFFL).u32(track.height.toLong() and 0xFFFFFFFFL).end()

        timing.edits?.let { edits ->
            val wideEdits = edits.any { it[0] > 0xFFFFFFFFL || it[1] > Int.MAX_VALUE }
            out.begin(EDTS).full(ELST, if (wideEdits) 1 else 0, 0).u32(edits.size.toLong())
            for (edit in edits) {
                if (wideEdits) out.u64(edit[0]).u64(edit[1]) else out.u32(edit[0]).u32(edit[1] and 0xFFFFFFFFL)
                out.u32(edit[2] and 0xFFFFFFFFL)
            }
            out.end().end()
        }

        out.begin(MDIA)
        val wideMedia = timing.mediaDuration > 0xFFFFFFFFL
        out.full(MDHD, if (wideMedia) 1 else 0, 0)
        times(out, wideMedia).u32(track.timescale)
        length(out, wideMedia, timing.mediaDuration)
        out.u16(track.language).u16(0).end()
        val name = when (track.handler) { VIDE -> "VideoHandler"; SOUN -> "SoundHandler"; else -> "DataHandler" }
        out.full(HDLR, 0, 0).u32(0).u32(track.handler.toLong()).zeros(12).ascii(name).u8(0).end()

        out.begin(MINF)
        when (track.handler) {
            VIDE -> out.full(VMHD, 0, 1).zeros(8).end()
            SOUN -> out.full(SMHD, 0, 0).u32(0).end()
            else -> out.full(NMHD, 0, 0).end()
        }
        out.begin(DINF).full(DREF, 0, 0).u32(1).full(URL, 0, 1).end().end().end()
        stbl(out, track, timing, dataStart, wide)
        out.end().end().end()
    }

    private fun stbl(out: Boxes, track: Track, timing: Timing, dataStart: Long, wide: Boolean) {
        val count = track.count()
        out.begin(STBL)
        out.begin(STSD).bytes(track.descriptions!!).end()

        val stts = Ints()
        for (i in 0 until count) runLength(stts, track.durations[i])
        out.full(STTS, 0, 0).u32((stts.size / 2).toLong()).ints(stts).end()

        var composed = timing.shift != 0L
        for (i in 0 until count) { if (composed) break; composed = track.offsets[i] != 0 }
        if (composed) {
            val ctts = Ints()
            for (i in 0 until count) runLength(ctts, (track.offsets[i] + timing.shift).toInt())
            out.full(CTTS, 0, 0).u32((ctts.size / 2).toLong()).ints(ctts).end()
        }

        if (!track.nonSync.isEmpty) {
            out.full(STSS, 0, 0).u32((count - track.nonSync.cardinality()).toLong())
            var i = track.nonSync.nextClearBit(0)
            while (i < count) { out.u32((i + 1).toLong()); i = track.nonSync.nextClearBit(i + 1) }
            out.end()
        }

        val stsc = Ints()
        track.chunks.forEachIndexed { c, chunk ->
            val entries = stsc.size / 3
            if (entries > 0 && stsc[stsc.size - 2] == chunk.count && stsc[stsc.size - 1] == chunk.description) return@forEachIndexed
            stsc.add(c + 1); stsc.add(chunk.count); stsc.add(chunk.description)
        }
        out.full(STSC, 0, 0).u32((stsc.size / 3).toLong()).ints(stsc).end()

        var uniform = count > 0 && track.sizes[0] != 0
        for (i in 1 until count) { if (!uniform) break; uniform = track.sizes[i] == track.sizes[0] }
        out.full(STSZ, 0, 0)
        if (uniform) out.u32(track.sizes[0].toLong()).u32(count.toLong()) else out.u32(0).u32(count.toLong()).ints(track.sizes)
        out.end()

        out.full(if (wide) CO64 else STCO, 0, 0).u32(track.chunks.size.toLong())
        for (chunk in track.chunks) if (wide) out.u64(dataStart + chunk.at) else out.u32(dataStart + chunk.at)
        out.end()
        out.end()
    }

    private fun runLength(table: Ints, value: Int) {
        if (table.size > 0 && table[table.size - 1] == value) table[table.size - 2] = table[table.size - 2] + 1
        else { table.add(1); table.add(value) }
    }

    private fun times(out: Boxes, wide: Boolean) = if (wide) out.u64(0).u64(0) else out.u32(0).u32(0)
    private fun length(out: Boxes, wide: Boolean, value: Long) { if (wide) out.u64(value) else out.u32(value) }

    private class Box(val type: Int, val data: ByteBuffer, val end: Int)

    private fun children(parent: ByteBuffer): List<Box> {
        val boxes = ArrayList<Box>()
        var at = 0
        val end = parent.limit()
        while (end - at >= 8) {
            var size = parent.getInt(at).toLong() and 0xFFFFFFFFL
            val type = parent.getInt(at + 4)
            var header = 8
            if (size == 1L) {
                if (end - at < 16) throw IOException("a box header is cut short")
                size = parent.getLong(at + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < header || size > end - at) throw IOException("a box runs past the box that holds it")
            boxes += Box(type, slice(parent, at + header, at + size.toInt()), at + size.toInt())
            at += size.toInt()
        }
        return boxes
    }

    private fun find(boxes: List<Box>, type: Int) = boxes.firstOrNull { it.type == type }

    private fun slice(buffer: ByteBuffer, from: Int, to: Int): ByteBuffer {
        val copy = buffer.duplicate()
        copy.limit(to)
        copy.position(from)
        return copy.slice()
    }

    private fun u32(buffer: ByteBuffer) = buffer.int.toLong() and 0xFFFFFFFFL

    private class Boxes {
        private var bytes = ByteArray(4096)
        private var size = 0
        private val open = IntArray(16)
        private var depth = 0

        fun begin(type: Int): Boxes { open[depth++] = size; return u32(0).u32(type.toLong()) }
        fun full(type: Int, version: Int, flags: Int) = begin(type).u32((version.toLong() shl 24) or flags.toLong())
        fun end(): Boxes { val start = open[--depth]; ByteBuffer.wrap(bytes).putInt(start, size - start); return this }
        private fun room(more: Int) { if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more)) }
        fun u8(value: Int): Boxes { room(1); bytes[size++] = value.toByte(); return this }
        fun u16(value: Int) = u8(value shr 8).u8(value)
        fun u32(value: Long): Boxes { room(4); ByteBuffer.wrap(bytes).putInt(size, value.toInt()); size += 4; return this }
        fun u64(value: Long) = u32(value ushr 32).u32(value)
        fun zeros(count: Int): Boxes { room(count); size += count; return this }
        fun ascii(text: String): Boxes { text.forEach { u8(it.code) }; return this }
        fun bytes(more: ByteArray): Boxes { room(more.size); System.arraycopy(more, 0, bytes, size, more.size); size += more.size; return this }
        fun bytes(more: ByteBuffer): Boxes {
            val copy = more.duplicate()
            copy.position(0)
            val length = copy.remaining()
            room(length)
            copy.get(bytes, size, length)
            size += length
            return this
        }
        fun ints(values: Ints): Boxes {
            room(values.size * 4)
            val buffer = ByteBuffer.wrap(bytes)
            for (i in 0 until values.size) buffer.putInt(size + i * 4, values[i])
            size += values.size * 4
            return this
        }
        fun bytes(): ByteArray = bytes.copyOf(size)
    }

    private class Ints {
        private var values = IntArray(256)
        var size = 0
        fun add(value: Int) { if (size == values.size) values = values.copyOf(size * 2); values[size++] = value }
        operator fun get(index: Int) = values[index]
        operator fun set(index: Int, value: Int) { values[index] = value }
    }
}
