package com.jasonernst.krcon

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class WebRConPacket(
    @JsonNames("Identifier") val identifier: Int,
    @JsonNames("Message") val message: String,
    @JsonNames("Name") val name: String = "",
    @JsonNames("Type") val type: String = "",
)
