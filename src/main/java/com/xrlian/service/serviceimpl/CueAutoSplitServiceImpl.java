package com.xrlian.service.serviceimpl;

import com.xrlian.service.CueAutoSplitService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.mozilla.universalchardet.UniversalDetector;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class FilesGetter extends SimpleFileVisitor<Path> {
    List<Path> returnPath = new ArrayList<>();

    @Override
    public FileVisitResult visitFileFailed(Path path, IOException e) {
        System.err.println(e);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path path, IOException e) {
        return FileVisitResult.CONTINUE;
    }
}

@Service
public class CueAutoSplitServiceImpl implements CueAutoSplitService {

    private static final String[] MUSIC_EXTENSIONS = {".ape", ".flac", ".wav"};

    @Override
    public void splitAudioInPath(Path path) {

    }

    private static void cueSplit(Path startingDir) {

        Path audioPath = null;
        try {
            audioPath = getAudioPath(startingDir);
        } catch (FileNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }

        Path cuePath = null;
        try {
            cuePath = getCuePath(startingDir);
        } catch (FileNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }

        try {
            splitAudioFile(startingDir, audioPath, cuePath);
        } catch (InterruptedException | IOException e) {
            System.err.println(e.getMessage());
            System.err.println("Failed to split the audio file.");
            System.exit(-1);
        }

        // create generated audio getter
        FilesGetter generatedAudioGetter = new FilesGetter() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                if (path.getFileName().toString().toLowerCase().startsWith("split-")) {
                    this.returnPath.add(path);
                    return FileVisitResult.CONTINUE;
                }
                return FileVisitResult.CONTINUE;
            }
        };

        Path convertedCuePath = null;
        try {
            convertedCuePath = getConvertedCue(startingDir, cuePath);
        } catch (InterruptedException | IOException e) {
            System.err.println(e.getMessage());
            System.err.println("Failed to convert the cue file.");
            System.exit(-1);
        }

        // find generated audios
        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, generatedAudioGetter);
        } catch (IOException e) {
            System.err.println("Failed to find the generated audio files.");
            System.exit(-1);
        }

        List<String> generatedAudios = generatedAudioGetter.returnPath.stream().map(e -> e.toString()).collect(Collectors.toList());
        generatedAudios.sort(Comparator.<String>naturalOrder());

        tagGeneratedFiles(startingDir, convertedCuePath, generatedAudios);
        renameAudioFilesUsingTag(generatedAudios);
    }

    private static void renameAudioFilesUsingTag(List<String> generatedAudios) {
        // rename files
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        for (String generatedAudio : generatedAudios)
            try {
                AudioFile f = AudioFileIO.read(new File(generatedAudio));
                Tag tag = f.getTag();
                String artist = tag.getFirst(FieldKey.ARTIST);
                String album = tag.getFirst(FieldKey.ALBUM);
                String title = tag.getFirst(FieldKey.TITLE);
                Path sourcePath = new File(generatedAudio).toPath();
                Files.move(sourcePath, sourcePath.resolveSibling(artist + " -  " +
                        album + " - " + title + ".flac"));
            } catch (CannotReadException | IOException | TagException | InvalidAudioFrameException | ReadOnlyFileException e) {
                System.err.println("Cannot run jaudiotagger to rename files.");
                System.exit(-1);
            }
    }

    private static void tagGeneratedFiles(Path startingDir, Path convertedCuePath, List<String> generatedAudios) {
        // add tags
        List<String> cuetagArguments = new ArrayList<>(Arrays.asList("cuetag.sh", convertedCuePath.toString()));

        cuetagArguments.addAll(generatedAudios);

        System.out.println("Adding tags...");
        System.out.println(cuetagArguments);

        try {
            ProcessBuilder cuetagProcessBuilder = new ProcessBuilder(cuetagArguments);
            cuetagProcessBuilder.directory(startingDir.toFile());
            cuetagProcessBuilder.inheritIO();
            Process cuetagProcess = cuetagProcessBuilder.start();

            try {
                cuetagProcess.waitFor();
            } catch (InterruptedException e) {
                System.err.println("Cannot run cuetag.sh.");
                System.exit(-1);
            }
        } catch (IOException e) {
            System.err.println("cuetag.sh cannot chdir to the directory specified.");
            System.exit(-1);
        }
    }

    private static Path getConvertedCue(Path startingDir, Path cuePath) throws InterruptedException, IOException {
        // convert cue file
        Path convertedCuePath = cuePath.resolveSibling("converted__.cue");
        if (!Files.exists(convertedCuePath)) {
            UniversalDetector detector = new UniversalDetector(null);

            try {
                detector.handleData(Files.readAllBytes(cuePath));
            } catch (IOException e) {
                throw new IOException("Cannot read the cue file.");
            }

            detector.dataEnd();
            String encoding = detector.getDetectedCharset();

            if (encoding == null) {
                System.out.println("Cannot detect CUE encoding, using gbk");
                encoding = "gbk";
            } else {
                System.out.println("Detected CUE encoding = " + encoding);
            }

            List<String> iconvArguments = new ArrayList<>(Arrays.asList("iconv",
                    "-f", encoding, "-t", "utf8", cuePath.toString()));

            try {
                ProcessBuilder iconvProcessBuilder = new ProcessBuilder(iconvArguments);
                iconvProcessBuilder.directory(startingDir.toFile());
                iconvProcessBuilder.redirectOutput(convertedCuePath.toFile());
                Process cuetagProcess = iconvProcessBuilder.start();

                try {
                    cuetagProcess.waitFor();
                } catch (InterruptedException e) {
                    throw new InterruptedException("Failed to run iconv.");
                }
            } catch (IOException e) {
                throw new IOException("iconv failed to chdir to the directory specified.");
            }
        }
        return convertedCuePath;
    }

    private static void splitAudioFile(Path startingDir, Path audioPath, Path cuePath) throws InterruptedException, IOException {
        // split
        List<String> shnsplitArguments = Arrays.asList("shnsplit", "-f", cuePath.toString(), "-o",
                "flac", "-d", startingDir.toString(), audioPath.toString());
        System.out.println("Splitting...");
        System.out.println(shnsplitArguments);
        try {
            ProcessBuilder shnsplitProcessBuilder = new ProcessBuilder(shnsplitArguments);
            shnsplitProcessBuilder.directory(startingDir.toFile());
            shnsplitProcessBuilder.inheritIO();
            Process shnsplitProcess = shnsplitProcessBuilder.start();

            try {
                shnsplitProcess.waitFor();
            } catch (InterruptedException e) {
                throw new InterruptedException("Cannot split the audio file.");
            }
        } catch (IOException e) {
            throw new IOException("shnsplit cannot chdir to the directory specified.");
        }
    }

    private static Path getCuePath(Path startingDir) throws FileNotFoundException {
        // create cue getter
        FilesGetter cueGetter = new FilesGetter() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                if (path.toString().toLowerCase().endsWith(".cue")) {
                    this.returnPath.add(path);
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        };


        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, cueGetter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (cueGetter.returnPath.size() == 0) {
            throw new FileNotFoundException("Not cue file found.");
        }

        Path audioPath = cueGetter.returnPath.get(0);
        return audioPath;
    }

    private static Path getAudioPath(Path startingDir) throws FileNotFoundException {
        // create audio getter
        FilesGetter audioGetter = new FilesGetter() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                for (String extension : MUSIC_EXTENSIONS) {
                    if (path.toString().toLowerCase().endsWith(extension)) {
                        this.returnPath.add(path);
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        };

        // Get the paths for audio and cue
        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, audioGetter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (audioGetter.returnPath.size() == 0) {
            throw new FileNotFoundException("Not audio file found.");
        }

        return audioGetter.returnPath.get(0);
    }
}
