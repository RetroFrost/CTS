package com.retrofrost.cubicalcompare.v5

import org.junit.Assert.*
import org.junit.Test

class PortableProjectBundleTest {
    @Test fun onlyKnownArchiveEntryNamesAreAllowed() {
        listOf("project.ccv5", "renderer/selected.renderer3",
            "audio/0000.mp3", "audio/0127.m4a", "audio/0004.bin"
        ).forEach { assertTrue("Should allow $it", PortableProjectBundle.allowedEntry(it)) }
        listOf("../project.ccv5", "/etc/passwd", "media/../asset.png",
            "audio/../../secret.txt", "audio/9999999.wav", "audio/0000.exe/../x",
            "Audio/0000.mp3", "renderer/other.renderer3", "audio/0000.",
            "audio\\0000.mp3", "scripts/install.sh", "project.ccv5/"
        ).forEach { assertFalse("Should reject $it", PortableProjectBundle.allowedEntry(it)) }
    }

    @Test fun rendererIdentityAndAudioOrderArePreservedByProjectJson() {
        val project=StudioModel.blank()
        project.Renderer3Id="ribbon.sample"
        project.Renderer3AssetName="pack:renderer/selected.renderer3"
        project.AudioTracks.add("pack:audio/0000.mp3")
        project.AudioTracks.add("pack:audio/0001.wav")
        project.Cards[0].ThumbnailTransform.Scale=2.25
        val loaded=StudioModel.parse(StudioModel.json(project))
        assertEquals("ribbon.sample",loaded.Renderer3Id)
        assertEquals("pack:renderer/selected.renderer3",loaded.Renderer3AssetName)
        assertEquals(listOf("pack:audio/0000.mp3","pack:audio/0001.wav"),loaded.AudioTracks)
        assertEquals(2.25,loaded.Cards[0].ThumbnailTransform.Scale,0.0001)
    }
}
