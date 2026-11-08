package com.genie.tts

internal class InMemoryCharacterStoreTest : CharacterStoreContract() {
    override fun create(): CharacterStore = InMemoryCharacterStore()
}
