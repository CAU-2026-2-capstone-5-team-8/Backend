package com.cau.capstone8.backend.book;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class CatalogSelectionImportRunnerTest {
    @Test void localRestartPreservesNewerSelections() {
        var service=mock(CatalogSelectionService.class);when(service.current()).thenReturn(List.of(Map.of("snapshotId","newer")));
        var env=new MockEnvironment();env.setActiveProfiles("local");
        new CatalogSelectionImportRunner(service,"old.json",env).run(null);
        verify(service,never()).activate(any());
    }
    @Test void explicitImportStillActivatesRequestedSelection() {
        var service=mock(CatalogSelectionService.class);var env=new MockEnvironment();env.setActiveProfiles("catalog-selection-import");
        new CatalogSelectionImportRunner(service,"selected.json",env).run(null);
        verify(service).activate(Path.of("selected.json"));
    }
}
