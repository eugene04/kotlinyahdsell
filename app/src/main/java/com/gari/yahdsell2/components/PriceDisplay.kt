package com.gari.yahdsell2.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PriceDisplay(
    price: Double,
    size: String = "large"
) {
    val priceString = String.format("%.2f", price)
    val (dollars, cents) = priceString.split(".")
    val formattedDollars = dollars.toInt().toString()

    val style = when (size) {
        "small" -> PriceStyle(
            currencySize = 14.sp,
            dollarSize = 18.sp,
            centSize = 14.sp,
            spacing = 2.dp
        )
        else -> PriceStyle(
            currencySize = 18.sp,
            dollarSize = 24.sp,
            centSize = 16.sp,
            spacing = 4.dp
        )
    }

    Row(
        modifier = Modifier.padding(top = style.spacing),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = "$",
            fontSize = style.currencySize,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = formattedDollars,
            fontSize = style.dollarSize,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp)
        )
        Text(
            text = ".${cents}",
            fontSize = style.centSize,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

data class PriceStyle(
    val currencySize: androidx.compose.ui.unit.TextUnit,
    val dollarSize: androidx.compose.ui.unit.TextUnit,
    val centSize: androidx.compose.ui.unit.TextUnit,
    val spacing: androidx.compose.ui.unit.Dp
)