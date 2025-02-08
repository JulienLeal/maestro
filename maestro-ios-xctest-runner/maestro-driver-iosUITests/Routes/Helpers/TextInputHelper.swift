import Foundation
import os

struct TextInputHelper {
    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier!,
        category: String(describing: Self.self)
    )
    
    private enum Constants {
        static let baseTypingSpeed: Int32 = 12 // Optimal balance between speed and reliability
        static let interKeyDelay: UInt64 = 15_000_000 // 15ms between key presses
        static let initialStabilizationDelay: UInt64 = 100_000_000 // 100ms after first character
    }
    
    static func inputText(_ text: String) async throws {
        guard !text.isEmpty else { return }
        
        // Split into individual characters for atomic handling
        let characters = Array(text)
        
        // Type first character with stabilization delay
        try await typeCharacter(String(characters[0]), initialDelay: true)
        
        // Process remaining characters
        for character in characters[1...] {
            try await Task.sleep(nanoseconds: Constants.interKeyDelay)
            try await typeCharacter(String(character))
        }
    }
    
    private static func typeCharacter(_ character: String, initialDelay: Bool = false) async throws {
        logger.info("Typing character: \(character)")
        
        var eventPath = PointerEventPath.pathForTextInput()
        eventPath.type(text: character, typingSpeed: Constants.baseTypingSpeed)
        
        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.add(eventPath)
        
        try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
        
        if initialDelay {
            // Slightly longer delay after first character to stabilize input
            try await Task.sleep(nanoseconds: Constants.initialStabilizationDelay)
        }
    }
}