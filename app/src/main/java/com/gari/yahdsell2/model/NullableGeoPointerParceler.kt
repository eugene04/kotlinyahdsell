package com.gari.yahdsell2.model

import android.os.Parcel
import com.google.firebase.firestore.GeoPoint
import kotlinx.parcelize.Parceler

// Parceler specifically for nullable GeoPoint?
object NullableGeoPointParceler : Parceler<GeoPoint?> {

    // Reads the nullability flag first, then potentially the GeoPoint
    override fun create(parcel: Parcel): GeoPoint? {
        val isNotNull = parcel.readInt() == 1
        return if (isNotNull) {
            // If not null, delegate creation to the non-nullable Parceler
            GeoPointParceler.create(parcel)
        } else {
            null
        }
    }

    // Writes the nullability flag first, then potentially the GeoPoint
    override fun GeoPoint?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeInt(0) // Write 0 to indicate null
        } else {
            parcel.writeInt(1) // Write 1 to indicate not null
            // Delegate writing the actual GeoPoint to the non-nullable Parceler
            // We use 'with' to call the extension function correctly
            with(GeoPointParceler) {
                this@write.write(parcel, flags)
            }
        }
    }
}
