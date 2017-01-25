package com.xrlian.commands;

import com.xrlian.service.CueAutoSplitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.annotation.CliAvailabilityIndicator;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

@Component
public class CueAutoSplitCommands implements CommandMarker {
    @Autowired
    private CueAutoSplitService cueAutoSplitService;

    @CliAvailabilityIndicator({"split"})
    public boolean isSplitAvailable() {
        return true;
    }

    @CliCommand(value = "split", help = "split the audio in the folder specified")
    public String split(
            @CliOption(key = {"", "path"}, mandatory = true,
                    help = "where the audio is located") String path
    ) {
        cueAutoSplitService.splitAudioInPath(Paths.get(path));
        return "Job done";
    }
}
