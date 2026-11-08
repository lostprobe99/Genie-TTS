package com.genie.tts.character
internal class InMemoryCharacterStoreTest : CharacterStoreContract() {
    override fun create(): CharacterStore = InMemoryCharacterStore()
}
