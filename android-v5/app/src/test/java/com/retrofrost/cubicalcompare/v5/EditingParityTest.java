package com.retrofrost.cubicalcompare.v5;
import org.junit.Test;
import static org.junit.Assert.*;
public class EditingParityTest {
 @Test public void csvRoundTripPreservesEveryLiveField() throws Exception {
  StudioModel.Project p=StudioModel.blank();StudioModel.Card c=p.Cards.get(0);
  c.Title="After \"one\", two";c.Description="First line\nSecond line";
  c.BadgeHeader="After";c.Badge="2.5";c.BadgeUnit="years";c.BadgeColor="#FF1155";
  c.ArtworkSource="https://www.flaticon.com/free-icon/example_123";
  StudioModel.Card restored=StudioModel.parseCsv(StudioModel.csv(p)).get(0);
  assertEquals(c.Title,restored.Title);assertEquals(c.Description,restored.Description);
  assertEquals(c.BadgeHeader,restored.BadgeHeader);assertEquals(c.Badge,restored.Badge);
  assertEquals(c.BadgeUnit,restored.BadgeUnit);assertEquals(c.ArtworkSource,restored.ArtworkSource);
  assertEquals(c.BadgeColor,restored.BadgeColor);
 }
 @Test public void bulkTransformsAreIndependentAndThumbnailSpecific(){
  StudioModel.Project p=StudioModel.blank();p.Cards.add(new StudioModel.Card());
  StudioModel.Card first=p.Cards.get(0);first.ImageTransform.X=123;first.ThumbnailTransform.Rotation=45;
  StudioModel.applyTransformToAll(p,first,false);
  assertEquals(123,p.Cards.get(1).ImageTransform.X,0);
  assertEquals(0,p.Cards.get(1).ThumbnailTransform.Rotation,0);
  p.Cards.get(1).ImageTransform.X=77;
  assertEquals(123,first.ImageTransform.X,0);
  StudioModel.applyTransformToAll(p,first,true);
  assertEquals(45,p.Cards.get(1).ThumbnailTransform.Rotation,0);
 }
 @Test public void undoRedoBranchingPreservesContent(){
  ProjectHistory h=new ProjectHistory();h.capture("a");h.capture("b");h.capture("c");
  assertEquals("b",h.undo());h.capture("b");assertEquals("c",h.redo());
  assertEquals("b",h.undo());h.capture("d");assertFalse(h.canRedo());
  assertEquals("b",h.undo());assertEquals("a",h.undo());assertNull(h.undo());
 }
 @Test public void nullableWindowsFieldsAndExportSettingsAreNormalized(){
  StudioModel.Project p=StudioModel.parse("{\"FormatVersion\":5,\"Cards\":[null,{\"Title\":null}],\"AudioTracks\":[null],\"VideoEncoder\":null,\"ExportWidth\":1921,\"SecondsPerCard\":3600}");
  assertEquals(1,p.Cards.size());assertEquals("",p.Cards.get(0).Title);
  assertTrue(p.AudioTracks.isEmpty());assertEquals("auto",p.VideoEncoder);
  assertEquals(1920,p.ExportWidth);assertEquals(3600,p.SecondsPerCard);
 }
 @Test public void timelinePayloadDoesNotCopyArtworkPerFrame(){
  StudioModel.Project p=StudioModel.blank();p.Cards.get(0).ArtworkBase64="do-not-send-image-binary";
  p.VideoDurationSeconds=3.5;
  String timeline=StudioModel.timelineJson(p);
  assertFalse(timeline.contains("do-not-send-image-binary"));assertFalse(timeline.contains("Artwork"));
  assertTrue(timeline.contains("VideoDurationSeconds"));assertTrue(timeline.contains("First comparison"));
 }
 @Test public void customExportSettingsSurviveSaving(){
  StudioModel.Project p=StudioModel.blank();p.VideoDurationSeconds=123.5;p.VideoEncoder="software";p.VideoBitrateMbps=12;
  StudioModel.Project q=StudioModel.parse(StudioModel.json(p));
  assertEquals(123.5,q.VideoDurationSeconds,0);assertEquals("software",q.VideoEncoder);assertEquals(12,q.VideoBitrateMbps);
 }
}
