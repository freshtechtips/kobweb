package playground.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.core.Page
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.forms.TextInput
import com.varabyte.kobweb.streams.rememberApiStream
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import playground.components.layouts.PageLayout

@Page
@Composable
fun EchoPage() {
    PageLayout("Echo test") {
        var lastEchoedText by remember { mutableStateOf("") }
        val stream = rememberApiStream("echo") { ctx ->
            lastEchoedText = ctx.text
        }

        Text("Please send some text to get echoed")
        var text by remember { mutableStateOf("") }
        fun sendTextAndClear() {
            stream.send(text)
            text = ""
        }

        TextInput(text, onTextChanged = { text = it }, onCommit = { sendTextAndClear() })
        P()
        Button(onClick = { sendTextAndClear() }, enabled = text.isNotBlank()) {
            Text("Send")
        }

        P()
        Text("Text from server: $lastEchoedText")
    }
}
