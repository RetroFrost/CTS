package com.retrofrost.cubicalcompare.v5;
import java.util.HashMap;
import java.util.Map;

/** Shared Android text catalogue. English source keys allow direct menu and inspector reuse. */
public final class UiText {
 private UiText(){}
 private static final Map<String,String[]> rows=new HashMap<>();
 private static void put(String key,String pt,String es,String fr,String de,String ru,String ja,String zh,String it,String uk){
  rows.put(key,new String[]{key,pt,es,fr,de,ru,ja,zh,it,uk});
 }
 static {
  put("Project","Projeto","Proyecto","Projet","Projekt","Проект","プロジェクト","项目","Progetto","Проєкт");
  put("Cards","Cartões","Tarjetas","Cartes","Karten","Карточки","カード","卡片","Schede","Картки");
  put("Card editor","Editor de cartão","Editor de tarjeta","Éditeur de carte","Karteneditor","Редактор карточки","カード編集","卡片编辑","Editor schede","Редактор карток");
  put("Thumbnail editor","Editor de miniaturas","Editor de miniaturas","Éditeur miniature","Miniatur-Editor","Редактор миниатюр","サムネイル編集","缩略图编辑","Editor miniature","Редактор мініатюр");
  put("Audio patterns","Padrões de áudio","Patrones de audio","Modèles audio","Audiomuster","Аудиошаблоны","音声パターン","音频模式","Modelli audio","Аудіошаблони");
  put("Export","Exportar","Exportar","Exporter","Export","Экспорт","書き出し","导出","Esporta","Експорт");
  put("Draw as artwork","Desenhar ilustração","Dibujar ilustración","Dessiner illustration","Artwork zeichnen","Рисовать иллюстрацию","描画アート","绘制插图","Disegna illustrazione","Малювати ілюстрацію");
  put("Appearance","Aparência","Apariencia","Apparence","Darstellung","Внешний вид","外観","外观","Aspetto","Вигляд");
  put("Language","Idioma","Idioma","Langue","Sprache","Язык","言語","语言","Lingua","Мова");
  put("Compatibility","Compatibilidade","Compatibilidad","Compatibilité","Kompatibilität","Совместимость","互換性","兼容性","Compatibilità","Сумісність");
  put("Video defaults","Definições de vídeo","Valores de vídeo","Paramètres vidéo","Videostandards","Параметры видео","動画設定","视频设置","Impostazioni video","Налаштування відео");
  put("Video settings","Definições de vídeo","Ajustes de vídeo","Paramètres vidéo","Videoeinstellungen","Настройки видео","動画設定","视频设置","Impostazioni video","Налаштування відео");
  put("Project name","Nome do projeto","Nombre del proyecto","Nom du projet","Projektname","Название проекта","プロジェクト名","项目名称","Nome progetto","Назва проєкту");
  put("Title","Título","Título","Titre","Titel","Название","タイトル","标题","Titolo","Назва");
  put("Badge header","Título do selo","Encabezado de insignia","En-tête badge","Abzeichenkopf","Заголовок значка","バッジ見出し","徽章标题","Intestazione badge","Заголовок значка");
  put("Badge value","Valor do selo","Valor de insignia","Valeur badge","Abzeichenwert","Значение значка","バッジ値","徽章数值","Valore badge","Значення значка");
  put("Badge unit","Unidade do selo","Unidad de insignia","Unité badge","Abzeicheneinheit","Единица значка","バッジ単位","徽章单位","Unità badge","Одиниця значка");
  put("Description","Descrição","Descripción","Description","Beschreibung","Описание","説明","描述","Descrizione","Опис");
  put("Image transform","Transformar imagem","Transformar imagen","Transformation d'image","Bildtransformation","Трансформация изображения","画像変形","图像变换","Trasforma immagine","Трансформація зображення");
  put("Thumbnail transform","Transformar miniatura","Transformar miniatura","Transformation miniature","Miniaturtransformation","Трансформация миниатюры","サムネイル変形","缩略图变换","Trasforma miniatura","Трансформація мініатюри");
  put("Thumbnail file","Ficheiro de miniatura","Archivo de miniatura","Fichier miniature","Miniaturdatei","Файл миниатюры","サムネイルファイル","缩略图文件","File miniatura","Файл мініатюри");
  put("Frame rate","Taxa de fotogramas","Fotogramas por segundo","Images par seconde","Bildrate","Частота кадров","フレームレート","帧率","Frequenza fotogrammi","Частота кадрів");
  put("Seconds per card","Segundos por cartão","Segundos por tarjeta","Secondes par carte","Sekunden pro Karte","Секунд на карточку","カードの秒数","每张卡片秒数","Secondi per scheda","Секунд на картку");
  put("Scale %","Escala %","Escala %","Échelle %","Skalierung %","Масштаб %","拡大率 %","缩放 %","Scala %","Масштаб %");
  put("Position X","Posição X","Posición X","Position X","Position X","Позиция X","位置 X","位置 X","Posizione X","Позиція X");
  put("Position Y","Posição Y","Posición Y","Position Y","Position Y","Позиция Y","位置 Y","位置 Y","Posizione Y","Позиція Y");
  put("Rotation","Rotação","Rotación","Rotation","Drehung","Поворот","回転","旋转","Rotazione","Поворот");
  put("Flip horizontally","Inverter horizontalmente","Voltear horizontalmente","Retourner horizontalement","Horizontal spiegeln","Отразить по горизонтали","左右反転","水平翻转","Rifletti orizzontalmente","Віддзеркалити горизонтально");
  put("Flip vertically","Inverter verticalmente","Voltear verticalmente","Retourner verticalement","Vertikal spiegeln","Отразить по вертикали","上下反転","垂直翻转","Rifletti verticalmente","Віддзеркалити вертикально");
  put("Reset transform","Repor transformação","Restablecer transformación","Réinitialiser","Transformation zurücksetzen","Сбросить преобразование","変形をリセット","重置变换","Ripristina","Скинути трансформацію");
  put("Badge colour","Cor do selo","Color de insignia","Couleur badge","Abzeichenfarbe","Цвет значка","バッジ色","徽章颜色","Colore badge","Колір значка");
  put("New project","Novo projeto","Nuevo proyecto","Nouveau projet","Neues Projekt","Новый проект","新規プロジェクト","新建项目","Nuovo progetto","Новий проєкт");
  put("Open .ccv5","Abrir .ccv5","Abrir .ccv5","Ouvrir .ccv5",".ccv5 öffnen","Открыть .ccv5",".ccv5を開く","打开 .ccv5","Apri .ccv5","Відкрити .ccv5");
  put("Save .ccv5","Guardar .ccv5","Guardar .ccv5","Enregistrer .ccv5",".ccv5 speichern","Сохранить .ccv5",".ccv5を保存","保存 .ccv5","Salva .ccv5","Зберегти .ccv5");
  put("Import CSV","Importar CSV","Importar CSV","Importer CSV","CSV importieren","Импорт CSV","CSV読込","导入CSV","Importa CSV","Імпорт CSV");
  put("＋ Add","＋ Adicionar","＋ Añadir","＋ Ajouter","＋ Hinzufügen","＋ Добавить","＋ 追加","＋ 添加","＋ Aggiungi","＋ Додати");
  put("Duplicate","Duplicar","Duplicar","Dupliquer","Duplizieren","Дублировать","複製","复制","Duplica","Дублювати");
  put("Remove selected card","Remover cartão","Eliminar tarjeta","Supprimer carte","Karte entfernen","Удалить карточку","カード削除","删除卡片","Rimuovi scheda","Видалити картку");
  put("↑ Move","↑ Subir","↑ Subir","↑ Monter","↑ Nach oben","↑ Вверх","↑ 上へ","↑ 上移","↑ Su","↑ Вгору");
  put("↓ Move","↓ Descer","↓ Bajar","↓ Descendre","↓ Nach unten","↓ Вниз","↓ 下へ","↓ 下移","↓ Giù","↓ Вниз");
  put("Import artwork","Importar imagem","Importar imagen","Importer illustration","Bild importieren","Добавить изображение","画像追加","导入插画","Importa immagine","Імпорт зображення");
  put("Remove artwork","Remover imagem","Eliminar imagen","Retirer image","Bild entfernen","Удалить изображение","画像削除","移除插画","Rimuovi immagine","Прибрати зображення");
  put("Import thumbnail image","Importar miniatura","Importar miniatura","Importer miniature","Miniatur importieren","Импорт миниатюры","サムネイル取込","导入缩略图","Importa miniatura","Імпорт мініатюри");
  put("Use main artwork","Usar imagem principal","Usar imagen principal","Utiliser image principale","Hauptbild verwenden","Использовать основное изображение","メイン画像を使用","使用主图","Usa immagine principale","Використати основне зображення");
  put("Copy main transform","Copiar transformação principal","Copiar transformación principal","Copier transformation principale","Haupttransformation kopieren","Копировать основную трансформацию","変形をコピー","复制主变换","Copia trasformazione","Копіювати трансформацію");
  put("Save Pattern (.m3u)","Guardar padrão (.m3u)","Guardar patrón (.m3u)","Enregistrer modèle (.m3u)","Muster speichern (.m3u)","Сохранить шаблон (.m3u)","パターン保存 (.m3u)","保存模式 (.m3u)","Salva modello (.m3u)","Зберегти шаблон (.m3u)");
  put("Import Pattern (.m3u)","Importar padrão (.m3u)","Importar patrón (.m3u)","Importer modèle (.m3u)","Muster importieren (.m3u)","Импорт шаблона (.m3u)","パターン取込 (.m3u)","导入模式 (.m3u)","Importa modello (.m3u)","Імпорт шаблону (.m3u)");
  put("Volume %","Volume %","Volumen %","Volume %","Lautstärke %","Громкость %","音量 %","音量 %","Volume %","Гучність %");
  put("Dark","Escuro","Oscuro","Sombre","Dunkel","Тёмная","ダーク","深色","Scuro","Темна");
  put("Light","Claro","Claro","Clair","Hell","Светлая","ライト","浅色","Chiaro","Світла");
  put("Follow system appearance","Seguir sistema","Seguir el sistema","Suivre le système","Systemdesign verwenden","Как в системе","システムに従う","跟随系统","Segui sistema","Як у системі");
  put("Brush size","Tamanho do pincel","Tamaño de pincel","Taille du pinceau","Pinselgröße","Размер кисти","ブラシサイズ","画笔大小","Dimensione pennello","Розмір пензля");
  put("Software canvas preview (older GPU support)","Pré-visualização por software (GPUs antigas)","Vista previa por software (GPU antiguas)","Aperçu logiciel (ancien GPU)","Softwarevorschau (alte GPU)","Программный предпросмотр (старые GPU)","ソフトウェア描画（旧GPU）","软件预览（旧显卡）","Anteprima software (GPU vecchie)","Програмний перегляд (старі GPU)");
 }
 public static String t(String source,int language){
  int i=Math.max(0,Math.min(9,language));
  String[] exact=rows.get(source);if(exact!=null)return exact[i];
  if(source.startsWith("Cards  •  "))return t("Cards",i)+source.substring("Cards".length());
  if(source.startsWith("Language · "))return t("Language",i)+source.substring("Language".length());
  return source;
 }
}
