package dev.anidroid

/** Pick the stream closest to a wanted height. Adaptive (height 0) streams honor a cap by themselves. */
fun chooseStream(streams: List<Stream>, wanted: Int): Stream? {
    if(wanted<=0) return streams.firstOrNull {it.height==0} ?: streams.maxByOrNull {it.height}
    streams.firstOrNull {it.height==wanted}?.let {return it}
    streams.firstOrNull {it.height==0}?.let {return it}
    return streams.filter {it.height in 1..wanted}.maxByOrNull {it.height} ?: streams.minByOrNull {it.height}
}
