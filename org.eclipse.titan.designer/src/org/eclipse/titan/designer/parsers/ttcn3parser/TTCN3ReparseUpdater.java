/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.parsers.ttcn3parser;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenFactory;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.UnbufferedCharStream;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.titan.common.logging.ErrorReporter;
import org.eclipse.titan.common.parsers.SyntacticErrorStorage;
import org.eclipse.titan.common.parsers.TITANMarker;
import org.eclipse.titan.common.parsers.TitanListener;
import org.eclipse.titan.designer.GeneralConstants;
import org.eclipse.titan.designer.AST.Location;
import org.eclipse.titan.designer.AST.MarkerHandler;
import org.eclipse.titan.designer.AST.NULL_Location;
import org.eclipse.titan.designer.parsers.ParserMarkerSupport;
import org.eclipse.titan.designer.parsers.ParserUtilities;
import org.eclipse.titan.designer.preferences.PreferenceConstants;
import org.eclipse.titan.designer.productUtilities.ProductConstants;

/**
 * This class directs the incremental parsing. Stores all information about the nature and size of the damage done to the system, helps in reparsing
 * only the needed part of the file. And also takes care of cleaning and reporting errors inside the damaged area.
 *
 * @author Kristof Szabados
 * @author Arpad Lovassy
 */
public final class TTCN3ReparseUpdater {

	/** Errors from the parser (indicating syntax errors). */
	private List<SyntacticErrorStorage> mErrors;

	/** syntactic warnings and errors created by the parser. */
	private List<TITANMarker> warningsAndErrors;
	/** list of markers marking the locations of unsupported constructs. */
	private List<TITANMarker> unsupportedConstructs;
	/** map of markers marking the locations of unsupported constructs. */
	private Map<IFile, List<TITANMarker>> unsupportedConstructMap;

	/** The file being checked. */
	private final IFile file;
	/** The string form of the document. */
	private final String code;
	/** The line in which the actual damage starts. */
	private int firstLine;
	/** The amount to shift any line which comes after the first line. */
	private int lineShift;
	/** The believed start of the interval which might be effected by the modification. */
	private int modificationStartOffset;
	/** The believed end of the interval which might be effected by the modification. */
	private int modificationEndOffset;
	/** The amount of shift caused by the modification itself. */
	private int shift;

	// Additional information
	/**
	 * Stores if the name of entity has changed or not. This information can indicate a level higher, that a uniqueness check might be needed
	 */
	private boolean namechanged = false;

	public TTCN3ReparseUpdater(final IFile file, final String code, final int firstLine, final int lineShift, final int startOffset, final int endOffset, final int shift) {
		this.file = file;
		this.code = code;
		this.firstLine = firstLine;
		this.lineShift = lineShift;
		this.modificationStartOffset = startOffset;
		this.modificationEndOffset = endOffset;
		this.shift = shift;
		unsupportedConstructs = new ArrayList<TITANMarker>();
		unsupportedConstructMap = new ConcurrentHashMap<IFile, List<TITANMarker>>();
	}

	public void setUnsupportedConstructs(final Map<IFile, List<TITANMarker>> unsupportedConstructMap) {
		this.unsupportedConstructMap = unsupportedConstructMap;
		if (unsupportedConstructMap.containsKey(file)) {
			unsupportedConstructs = unsupportedConstructMap.get(file);
		}
	}

	public final int getDamageStart() {
		return modificationStartOffset;
	}

	public final int getDamageEnd() {
		return modificationEndOffset;
	}

	public final String getCode() {
		return code;
	}

	public final int getFirstLine() {
		return firstLine;
	}

	public final int getLineShift() {
		return lineShift;
	}

	public final int getShift() {
		return shift;
	}

	public final boolean isAffected(final Location location) {
		return location.getEndOffset() > modificationStartOffset;
	}

	public final boolean isAffectedAppended(final Location location) {
		return location.getEndOffset() >= modificationStartOffset;
	}

	public final boolean envelopsDamage(final Location location) {
		return location.getOffset() < modificationStartOffset && location.getEndOffset() >= modificationEndOffset;
	}

	public final boolean isDamaged(final Location location) {
		return location.getEndOffset() >= modificationStartOffset && modificationEndOffset >= location.getOffset();
	}

	// only extension on the end shall be allowed
	public final boolean isExtending(final Location location) {
		return location.getEndOffset() == modificationStartOffset;
	}

	public final void updateLocation(final Location location) {
		if (NULL_Location.INSTANCE == location) {
			return;
		}

		int offset = location.getOffset();
		if (offset >= modificationStartOffset) {
			location.setOffset(Math.max(offset + shift, modificationStartOffset));
		}
		offset = location.getEndOffset();
		if (offset > modificationStartOffset) {
			location.setEndOffset(Math.max(offset + shift, modificationStartOffset));
		}
		final int line = location.getLine();
		if (line > firstLine) {
			location.setLine(line + lineShift);
		} else if (line == firstLine && offset > modificationStartOffset) {
			location.setLine(line + lineShift);
		}
	}

	/**
	 * Checks if the first TTCN-3 lexical token in the substring, that covers the possibly changed interval of the document belongs to a given list of
	 * expected tokens or not.
	 *
	 * @param followSet the possible tokens that can follow the element before the suspected
	 *
	 * @return true if the first lexical token is part of the followset, false otherwise
	 * */
	public boolean startsWithFollow(final List<Integer> followSet) {
		if (followSet == null || followSet.isEmpty()) {
			return false;
		}

		if (code == null) {
			return false;
		}

		final int line = getLineOfOffset(code, modificationStartOffset);
		final int column = getPositionInLine(code, modificationStartOffset);
		String substring;
		if (code.length() <= modificationEndOffset + shift) {
			substring = code.substring(modificationStartOffset);
		} else {
			substring = code.substring(modificationStartOffset, modificationEndOffset + shift);
		}

		final Reader reader = new StringReader(substring);
		final CharStream charStream = new UnbufferedCharStream(reader);
		final Ttcn3Lexer lexer = new Ttcn3Lexer(charStream);
		lexer.setTokenFactory( new CommonTokenFactory( true ) );
		lexer.setLine( line + 1 );
		lexer.setCharPositionInLine(column);
		lexer.initRootInterval(modificationEndOffset - modificationStartOffset + 1);

		final Token token = lexer.nextToken();
		if (token == null) {
			return false;
		}

		return followSet.contains( token.getType() );
	}

	/**
	 * Checks if the last TTCN-3 lexical token in the substring, that covers the possibly changed interval of the document belongs to a given list of
	 * expected tokens or not.
	 *
	 * @param followSet the possible tokens that can prepend the element before the suspected
	 *
	 * @return true if the first lexical token is part of the followset, false otherwise
	 * */
	public boolean endsWithToken(final List<Integer> followSet) {
		if (followSet == null || followSet.isEmpty()) {
			return false;
		}

		if (code == null) {
			return false;
		}

		final int line = getLineOfOffset(code, modificationStartOffset);
		final int column = getPositionInLine(code, modificationStartOffset);
		String substring;
		if (code.length() <= modificationEndOffset + shift) {
			substring = code.substring(modificationStartOffset);
		} else {
			substring = code.substring(modificationStartOffset, modificationEndOffset + shift);
		}

		final Reader reader = new StringReader(substring);
		final CharStream charStream = new UnbufferedCharStream(reader);
		final Ttcn3Lexer lexer = new Ttcn3Lexer(charStream);
		lexer.setTokenFactory( new CommonTokenFactory( true ) );
		lexer.setLine( line + 1 );
		lexer.setCharPositionInLine(column);
		lexer.initRootInterval(modificationEndOffset - modificationStartOffset + 1);

		final Token token = lexer.nextToken();
		if (token == null) {
			return false;
		}

		return followSet.contains( token.getType() );
	}

	public final void extendDamagedRegion(final Location location) {
		if (location.getOffset() < modificationStartOffset) {
			modificationStartOffset = location.getOffset();
		}
		if (location.getEndOffset() > modificationEndOffset) {
			modificationEndOffset = location.getEndOffset();
		}
	}

	public final void extendDamagedRegion(final int start, final int end) {
		if (start < modificationStartOffset) {
			modificationStartOffset = start;
		}
		if (end > modificationEndOffset) {
			modificationEndOffset = end;
		}
	}

	public final void extendDamagedRegionTillFileEnd() {
		modificationEndOffset = code.length() - shift;
	}

	public final void maxDamage() {
		modificationStartOffset = 0;
		modificationEndOffset = code.length() - shift;
	}

	public void setNameChanged(final boolean namechanged) {
		this.namechanged = namechanged;
	}

	public boolean getNameChanged() {
		return namechanged;
	}

	protected int measureIntervallDamage() {
		String substring;
		if (code.length() <= modificationEndOffset + shift) {
			substring = code.substring(modificationStartOffset);
		} else {
			substring = code.substring(modificationStartOffset, modificationEndOffset + shift);
		}

		final int rangeEnd = substring.length();
		int nextPos = 0;
		boolean insideString = false;
		boolean insideSingleComment = false;
		boolean insideMultiComment = false;
		final Stack<String> elements = new Stack<String>();
		int unclosedStarting = 0;
		int unclosedEnding = 0;
		try {
			while (nextPos < rangeEnd) {
				switch (substring.charAt(nextPos)) {
				case '(':
					elements.push("(");
					unclosedStarting++;
					break;
				case ')':
					if (!elements.isEmpty() && "(".equals(elements.peek())) {
						elements.pop();
						unclosedStarting--;
					} else {
						elements.push(")");
						unclosedEnding++;
					}
					break;
				case '[':
					elements.push("[");
					unclosedStarting++;
					break;
				case ']':
					if (!elements.isEmpty() && "[".equals(elements.peek())) {
						elements.pop();
						unclosedStarting--;
					} else {
						elements.push("]");
						unclosedEnding++;
					}
					break;
				case '{':
					elements.push("{");
					unclosedStarting++;
					break;
				case '}':
					if (!elements.isEmpty() && "{".equals(elements.peek())) {
						elements.pop();
						unclosedStarting--;
					} else {
						elements.push("}");
						unclosedEnding++;
					}
					break;
				case '/':
					if (nextPos + 1 < rangeEnd) {
						switch (substring.charAt(nextPos + 1)) {
						case '*':
							insideMultiComment = true;
							nextPos += 2;
							while (nextPos < rangeEnd
									&& ('*' != substring.charAt(nextPos) || nextPos + 1 >= rangeEnd || '/' != substring.charAt(nextPos + 1))) {
								nextPos++;
							}
							if (nextPos < rangeEnd) {
								insideMultiComment = false;
							} else {
								nextPos++;
							}
							break;
						case '/':
							insideSingleComment = true;
							nextPos += 2;
							while (nextPos < rangeEnd && '\n' != substring.charAt(nextPos)) {
								nextPos++;
							}
							if (nextPos < rangeEnd) {
								insideSingleComment = false;
							}
							break;
						default:
							break;
						}
					}
					break;
				case '"':
					insideString = true;
					nextPos++;
					while (nextPos < rangeEnd && ('\"' != substring.charAt(nextPos) || '\"' == substring.charAt(nextPos - 1))) {
						if('\"' != substring.charAt(nextPos)) {
							nextPos++;
						} else if (nextPos + 1 < rangeEnd && '\"' == substring.charAt(nextPos + 1)) {
							nextPos += 2;
						} else {
							break;
						}
					}
					if (nextPos < rangeEnd) {
						insideString = false;
					}
					break;
				case '#':
					insideSingleComment = true;
					nextPos++;
					while (nextPos < rangeEnd && '\n' != substring.charAt(nextPos)) {
						nextPos++;
					}
					if (nextPos < rangeEnd) {
						insideSingleComment = false;
					}
					break;
				default:
					break;
				}
				nextPos++;
			}
		} catch (IndexOutOfBoundsException e) {
			ErrorReporter.logExceptionStackTrace(e);
		}

		if (insideSingleComment || insideMultiComment || insideString) {
			return Integer.MAX_VALUE;
		}

		return Math.max(unclosedStarting, unclosedEnding);
	}

	/**
	 * Specific part of marker handling
	 */
	private void reportSpecificSyntaxErrors() {
		if (mErrors != null) {
			final Location temp = new Location(file, firstLine, modificationStartOffset, modificationEndOffset + shift);
			for (int i = 0; i < mErrors.size(); i++) {
				ParserMarkerSupport.createOnTheFlySyntacticMarker(file, mErrors.get(i), IMarker.SEVERITY_ERROR, temp);
			}
		}
	}

	public final void reportSyntaxErrors() {
		reportSpecificSyntaxErrors();
		if (warningsAndErrors != null) {
			for (final TITANMarker marker : warningsAndErrors) {
				if (file.isAccessible()) {
					final Location location = new Location(file, marker.getLine(), marker.getOffset(), marker.getEndOffset());
					location.reportExternalProblem(marker.getMessage(), marker.getSeverity(), GeneralConstants.ONTHEFLY_SYNTACTIC_MARKER);
				}
			}
		}
		if (unsupportedConstructs != null && !unsupportedConstructs.isEmpty()) {
			final Iterator<TITANMarker> iterator = unsupportedConstructs.iterator();
			while (iterator.hasNext()) {
				final TITANMarker marker = iterator.next();
				if (marker.getOffset() >= modificationEndOffset) {
					marker.setOffset(marker.getOffset() + shift);
					marker.setEndOffset(marker.getEndOffset() + shift);
				}
			}
			unsupportedConstructMap.put(file, unsupportedConstructs);
		}
	}

	/**
	 * @param text the source text, where the search is done
	 * @param offset the offset or position of the character in the string. This is the character index, starts from 0.
	 * @return The line number of the offset. Line number starts from 0.
	 */
	public static final int getLineOfOffset(final String text, final int offset) {
		int lineCounter = 0;
		for (int i = 0; i < offset; i++) {
			if ('\n' == text.charAt(i)) {
				lineCounter++;
			}
		}

		return lineCounter;
	}

	/**
	 * @param text the source text, where the search is done
	 * @param offset the offset or position of the character in the string. This is the character index, starts from 0.
	 * @return The column number of the offset. Column number starts from 0.
	 */
	public static final int getPositionInLine(final String text, final int offset) {
		int columnCounter = 0;
		for (int i = offset - 1; i >= 0; i--) {
			if ('\n' == text.charAt(i)) {
				return columnCounter;
			}

			columnCounter++;
		}

		return columnCounter;
	}

	public int parse(final ITTCN3ReparseBase userDefined) {
		if (modificationStartOffset == modificationEndOffset + shift) {
			return 0;
		}
		// double wideparsing = System.nanoTime();
		mErrors = null;
		warningsAndErrors = null;
		final Iterator<TITANMarker> iterator = unsupportedConstructs.iterator();
		while (iterator.hasNext()) {
			final TITANMarker marker = iterator.next();
			if ((marker.getOffset() > modificationStartOffset && marker.getOffset() <= modificationEndOffset)
					|| (marker.getEndOffset() > modificationStartOffset && marker.getEndOffset() <= modificationEndOffset)) {
				iterator.remove();
			}
		}

		MarkerHandler.markAllOnTheFlyMarkersForRemoval(file, modificationStartOffset, modificationEndOffset + shift);

		if (code == null) {
			return Integer.MAX_VALUE;
		}

		final int line = getLineOfOffset(code, modificationStartOffset);
		String substring;
		if (code.length() <= modificationEndOffset + shift) {
			substring = code.substring(modificationStartOffset);
		} else {
			substring = code.substring(modificationStartOffset, modificationEndOffset + shift);
		}

		final IPreferencesService prefs = Platform.getPreferencesService();
		final boolean realtimeEnabled = prefs.getBoolean(ProductConstants.PRODUCT_ID_DESIGNER, PreferenceConstants.ENABLEREALTIMEEXTENSION, false, null);

		final Reader reader = new StringReader(substring);
		final CharStream charStream = new UnbufferedCharStream(reader);
		final Ttcn3Lexer lexer = new Ttcn3Lexer(charStream);
		lexer.setTokenFactory( new CommonTokenFactory( true ) );
		lexer.initRootInterval(modificationEndOffset - modificationStartOffset + 1);
		if (realtimeEnabled) {
			lexer.enableRealtime();
		}

		// lexer and parser listener
		final TitanListener parserListener = new TitanListener();
		// remove ConsoleErrorListener
		lexer.removeErrorListeners();
		lexer.addErrorListener(parserListener);

		// 1. Previously it was UnbufferedTokenStream(lexer), but it was changed to BufferedTokenStream, because UnbufferedTokenStream seems to be unusable. It is an ANTLR 4 bug.
		// Read this: https://groups.google.com/forum/#!topic/antlr-discussion/gsAu-6d3pKU
		// pr_PatternChunk[StringBuilder builder, boolean[] uni]:
		//   $builder.append($v.text); <-- exception is thrown here: java.lang.UnsupportedOperationException: interval 85..85 not in token buffer window: 86..341
		// 2. Changed from BufferedTokenStream to CommonTokenStream, otherwise tokens with "-> channel(HIDDEN)" are not filtered out in lexer.
		final CommonTokenStream tokenStream = new CommonTokenStream( lexer );

		final Ttcn3Reparser parser = new Ttcn3Reparser( tokenStream );
		ParserUtilities.setBuildParseTree( parser );

		lexer.setActualFile(file);
		parser.setActualFile(file);
		parser.setProject(file.getProject());
		parser.setOffset( modificationStartOffset );
		parser.setLine( line + 1 );

		// remove ConsoleErrorListener
		parser.removeErrorListeners();
		parser.addErrorListener( parserListener );

		// This is added because of the following ANTLR 4 bug:
		// Memory Leak in PredictionContextCache #499
		// https://github.com/antlr/antlr4/issues/499
		final DFA[] decisionToDFA = parser.getInterpreter().decisionToDFA;
		parser.setInterpreter(new ParserATNSimulator(parser, parser.getATN(), decisionToDFA, new PredictionContextCache()));

		//try SLL mode
		try {
			parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
	
			userDefined.reparse(parser);
			mErrors = parserListener.getErrorsStored();
			warningsAndErrors = parser.getWarningsAndErrors();
			unsupportedConstructs.addAll(parser.getUnsupportedConstructs());
		} catch (RecognitionException e) {
			// quit
		}

		if (!warningsAndErrors.isEmpty() || !mErrors.isEmpty()) {
			//SLL mode might have failed, try LL mode
			try {
				final CharStream charStream2 = new UnbufferedCharStream( reader );
				lexer.setInputStream(charStream2);
				//lexer.reset();
				parser.reset();
				parserListener.reset();
				parser.getInterpreter().setPredictionMode(PredictionMode.LL);
				userDefined.reparse(parser);
				mErrors = parserListener.getErrorsStored();
				warningsAndErrors = parser.getWarningsAndErrors();
				unsupportedConstructs.addAll(parser.getUnsupportedConstructs());

			} catch(RecognitionException e) {

			}
		}

		int result = measureIntervallDamage();
		if(!parser.isErrorListEmpty()){
			++result;
		}

		return result;
	}
}
