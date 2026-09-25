package app.lernet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.ui.theme.LerNetLine
import app.lernet.ui.theme.LerNetWhite
import androidx.compose.ui.graphics.Color

@Composable
fun LerNetLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(6.dp, shape, ambientColor = LerNetWhite.copy(alpha = 0.12f), spotColor = LerNetWhite.copy(alpha = 0.2f))
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, LerNetLine, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "L",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.50f).sp,
        )
    }
}
