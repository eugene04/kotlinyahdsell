package com.gari.yahdsell2.model

import android.os.Parcel
import kotlinx.parcelize.Parceler
import com.google.firebase.firestore.GeoPoint

object GeoPointParceler : Parceler<GeoPoint> {
    override fun create(parcel: Parcel): GeoPoint {
        val lat = parcel.readDouble()
        val lng = parcel.readDouble()
        return GeoPoint(lat, lng)
    }

    override fun GeoPoint.write(parcel: Parcel, flags: Int) {
        parcel.writeDouble(latitude)
        parcel.writeDouble(longitude)
    }
}