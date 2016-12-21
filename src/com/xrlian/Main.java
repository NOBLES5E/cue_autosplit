package com.xrlian;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
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

public class Main {
    private static final String[] MUSIC_EXTENSIONS = {".ape", ".flac", ".wav"};

    public static void main(String[] args) {
        // initiate path to traverse
        Path startingDir = Paths.get(args[0]);

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

        // Get the paths for audio and cue
        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, audioGetter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Path audioPath = audioGetter.returnPath.get(0);

        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, cueGetter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Path cuePath = cueGetter.returnPath.get(0);

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
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
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

        // convert cue file
        Path convertedCuePath = cuePath.resolveSibling("converted__.cue");
        if (!Files.exists(convertedCuePath)) {
            UniversalDetector detector = new UniversalDetector(null);
            try {
                detector.handleData(Files.readAllBytes(cuePath));
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
                        e.printStackTrace();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // find generated audios
        try {
            Files.walkFileTree(startingDir, Collections.emptySet(), 1, generatedAudioGetter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        List<String> generatedAudios = generatedAudioGetter.returnPath.stream().map(e -> e.toString()).collect(Collectors.toList());
        generatedAudios.sort(Comparator.<String>naturalOrder());


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
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // rename files
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        for (String generatedAudio : generatedAudios) {
            try {
                AudioFile f = AudioFileIO.read(new File(generatedAudio));
                Tag tag = f.getTag();
                String artist = tag.getFirst(FieldKey.ARTIST);
                String album = tag.getFirst(FieldKey.ALBUM);
                String title = tag.getFirst(FieldKey.TITLE);
                Path sourcePath = new File(generatedAudio).toPath();
                Files.move(sourcePath, sourcePath.resolveSibling(artist + " -  " +
                        album + " - " + title + ".flac"));
            } catch (CannotReadException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (TagException e) {
                e.printStackTrace();
            } catch (ReadOnlyFileException e) {
                e.printStackTrace();
            } catch (InvalidAudioFrameException e) {
                e.printStackTrace();
            }
        }
    }

}
