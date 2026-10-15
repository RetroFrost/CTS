package com.retrofrost.cubicalcompare.v5

import org.junit.Assert.*
import org.junit.Test

class Zipack2BridgeTest {
    @Test fun acceptedWindowsZipack2EntryPaths(){
        for(path in listOf("manifest.json","megapack.json","thumbnail.png",
            "artwork/contact-sheet-001.png","artwork/sheet-7.jpg","audio/soundtrack.mp3")){
            assertTrue("Rejected valid entry "+path,Zipack2Bridge.safe(path))
        }
    }
    @Test fun dangerousArchiveEntriesRejected(){
        for(path in listOf("../manifest.json","/manifest.json","artwork/../manifest.json",
            "artwork//contact-sheet-001.png","a/./b","C:\\windows\\file","./manifest.json",
            "audio/../soundtrack.mp3","artwork/","evil\u0000path")){
            assertFalse("Accepted unsafe entry "+path,Zipack2Bridge.safe(path))
        }
    }
    @Test fun modelPreservesVideoAndThumbnailTransformsAndBadgeFields(){
        val project=StudioModel.blank()
        val card=project.Cards[0]
        card.BadgeHeader="After"
        card.Badge="12"
        card.BadgeUnit="hours"
        card.ImageTransform.Scale=2.5
        card.ThumbnailTransform.Rotation=25.0
        val saved=StudioModel.parse(StudioModel.json(project)).Cards[0]
        assertEquals("After",saved.BadgeHeader)
        assertEquals("12",saved.Badge)
        assertEquals("hours",saved.BadgeUnit)
        assertEquals(2.5,saved.ImageTransform.Scale,0.001)
        assertEquals(25.0,saved.ThumbnailTransform.Rotation,0.001)
    }
}
