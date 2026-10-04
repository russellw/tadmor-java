package com.belunaro.tadmor.api;

import java.util.List;
import java.util.Map;

import com.belunaro.tadmor.service.DocumentKind;
import com.belunaro.tadmor.service.DocumentService;
import com.belunaro.tadmor.service.DocumentService.DocumentRequest;
import com.belunaro.tadmor.service.PostingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The endpoints every document with lines shares (spec/api.md §5.9). Each
 * collection is a subclass that names its path and binds the kind's request
 * body (I), read shape (D), and line shape (L); Spring resolves the type
 * variables against the subclass. Unposting is for administrators only
 * (SecurityConfig).
 */
abstract class LineDocumentController<I extends DocumentRequest, D, L> {

	private final DocumentKind kind;
	private final Class<D> shape;
	private final Class<L> lineShape;
	private final DocumentService documents;
	private final PostingService posting;

	LineDocumentController(DocumentKind kind, Class<D> shape, Class<L> lineShape, DocumentService documents,
			PostingService posting) {
		this.kind = kind;
		this.shape = shape;
		this.lineShape = lineShape;
		this.documents = documents;
		this.posting = posting;
	}

	@GetMapping
	public List<D> list() {
		return documents.list(kind, shape);
	}

	@GetMapping("/{id}")
	public D get(@PathVariable Id id) {
		return documents.get(kind, id.value(), shape);
	}

	@GetMapping("/{id}/lines")
	public List<L> lines(@PathVariable Id id) {
		return documents.lines(kind, id.value(), lineShape);
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody I in) {
		return Responses.createdId(documents.create(kind, in.document()));
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> update(@PathVariable Id id, @RequestBody I in) {
		documents.update(kind, id.value(), in.document());
		return Responses.noContent();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Id id) {
		documents.delete(kind, id.value());
		return Responses.noContent();
	}

	@PostMapping("/{id}/post")
	public Map<String, Integer> post(@PathVariable Id id) {
		return Map.of("journal_entry_id", posting.post(kind, id.value()));
	}

	@PostMapping("/{id}/unpost")
	public Map<String, Integer> unpost(@PathVariable Id id) {
		return Map.of("reversal_entry_id", posting.unpost(kind, id.value()));
	}
}
