package com.retrofrost.cubicalcompare.v5;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Arrays;
public class ProjectAndPlaylistTest {
 @Test public void windowsProjectRoundTrip(){
  StudioModel.Project p=StudioModel.blank();
  p.Cards.get(0).Title="Colour and motion";
  p.Cards.get(0).BadgeColor="#EE3344";
  p.Cards.get(0).ImageTransform.Scale=2.75;
  p.Cards.get(0).ThumbnailTransform.Rotation=-32;
  p.AudioTracks.add("content://audio/abc");
  p.AudioTracks.add("content://audio/abc");
  StudioModel.Project loaded=StudioModel.parse(StudioModel.json(p));
  assertEquals(5,loaded.FormatVersion);
  assertEquals("Colour and motion",loaded.Cards.get(0).Title);
  assertEquals("#EE3344",loaded.Cards.get(0).BadgeColor);
  assertEquals(2.75,loaded.Cards.get(0).ImageTransform.Scale,0.0001);
  assertEquals(-32,loaded.Cards.get(0).ThumbnailTransform.Rotation,0.0001);
  assertEquals(p.AudioTracks,loaded.AudioTracks);
 }
 @Test public void quotedMultilineCsv(){
  String csv="Number,Title,Badge Header,Badge Value,Badge Unit,Image,Description\r\n"
   +"1,\"Quote \"\"inside\"\", title\",Rank,1,%,,\"line one\nline two\"\r\n";
  try {
   List<StudioModel.Card> cards=StudioModel.parseCsv(csv);
   assertEquals(1,cards.size());
   assertEquals("Quote \"inside\", title",cards.get(0).Title);
   assertTrue(cards.get(0).Description.contains("\n"));
  }catch(Exception e){throw new AssertionError(e);}
 }
 @Test public void preserveM3uOrderAndUnicode(){
  List<String> tracks=Arrays.asList("content://music/tema1","content://music/música","content://music/tema1");
  assertEquals(tracks,M3uPattern.read(M3uPattern.save(tracks)));
 }
 @Test(expected=IllegalArgumentException.class)
 public void rejectRemoteM3u(){M3uPattern.read("#EXTM3U\nhttps://example.com/song.mp3");}
 @Test public void windowsJsonCompatibility(){
  StudioModel.Project p=StudioModel.parse("{\"FormatVersion\":5,\"Cards\":[{\"Title\":\"Card\",\"BadgeColor\":\"#CC4499\",\"ImageTransform\":{\"Scale\":1.75}}]}");
  assertEquals("Card",p.Cards.get(0).Title);
  assertEquals(1.75,p.Cards.get(0).transform(false).Scale,.001);
 }
}
