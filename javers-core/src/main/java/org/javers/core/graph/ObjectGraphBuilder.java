package org.javers.core.graph;

import org.javers.common.collections.Predicate;
import org.javers.common.validation.Validate;
import org.javers.core.metamodel.object.*;
import org.javers.core.metamodel.property.ManagedClass;
import org.javers.core.metamodel.property.Property;
import org.javers.core.metamodel.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.javers.common.validation.Validate.argumentIsNotNull;

/**
 * Creates graph based on ObjectNodes.
 * This is a stateful Builder (not a Service)
 *
 * @author bartosz walacik
 */
public class ObjectGraphBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ObjectGraphBuilder.class);

    private final TypeMapper typeMapper;
    private boolean built;
    private final EdgeBuilder edgeBuilder;
    private final NodeReuser nodeReuser = new NodeReuser();

    /**
     * uses default LiveCdoFactory
     */
    public ObjectGraphBuilder(TypeMapper typeMapper) {
        Validate.argumentIsNotNull(typeMapper);
        this.typeMapper = typeMapper;
        this.edgeBuilder = new EdgeBuilder(typeMapper, nodeReuser, new LiveCdoFactory());
    }

    public ObjectGraphBuilder(TypeMapper typeMapper, CdoFactory cdoFactory) {
        Validate.argumentsAreNotNull(typeMapper, cdoFactory);
        this.typeMapper = typeMapper;
        this.edgeBuilder = new EdgeBuilder(typeMapper, nodeReuser, cdoFactory);
    }

    @Override
    public String toString() {
        return super.toString();
    }

    /**
     * @param handle Client's domain object, instance of Entity or ValueObject.
     *               It should be root of an aggregate, tree root
     *               or any node in objects graph from where all other nodes are navigable
     * @return graph node
     */
    public ObjectNode buildGraph(Object handle) {
        Cdo cdo = edgeBuilder.asCdo(handle, null);
        logger.debug("building objectGraph for handle [{}] ...",cdo);

        ObjectNode root = buildNode(cdo);

        logger.debug("graph assembled, object nodes: {}, reused: {}",nodeReuser.nodesCount(), nodeReuser.reusedNodesCount());
        switchToBuilt();
        return root;
    }

    /**
     * recursive
     */
    private ObjectNode buildNode(Cdo cdo) {
        argumentIsNotNull(cdo);
        //logger.debug(".. creating node for: {}",cdo);

        ObjectNode node = edgeBuilder.buildNodeStub(cdo);
        continueIfStub(node);

        return node;
    }

    private void switchToBuilt() {
        if (built){
            throw new IllegalStateException("ObjectGraphBuilder is stateful builder (not a Service)");
        }
        built = true;
    }

    private void buildEdges(ObjectNode node) {
        buildSingleEdges(node);
        buildMultiEdges(node);
    }

    //recursion here
    private void continueIfStub(ObjectNode referencedNode) {
        if (referencedNode.isStub()){
            nodeReuser.saveForReuse(referencedNode);
            referencedNode.unstub();
            buildEdges(referencedNode); //recursion here
        }
    }

    private void buildSingleEdges(ObjectNode node) {
        for (Property singleRef : getSingleReferences(node.getManagedClass())) {
            if (node.isNull(singleRef)) {
                continue;
            }

            SingleEdge edge = edgeBuilder.buildSingleEdge(node, singleRef);

            continueIfStub(edge.getReference());

            node.addEdge(edge);
        }
    }

    private void buildMultiEdges(ObjectNode node) {
        for (Property containerProperty : getNonEmptyEnumerablesWithManagedClasses(node))  {
            EnumerableType enumerableType = typeMapper.getPropertyType(containerProperty);

            //looks like we have Container or Map with Entity references or Value Objects
            MultiEdge multiEdge = edgeBuilder.createMultiEdge(containerProperty, enumerableType, node, this);

            for (ObjectNode referencedNode : multiEdge.getReferences()){
                continueIfStub(referencedNode);
            }

            node.addEdge(multiEdge);
        }
    }

    private List<Property> getSingleReferences(ManagedClass managedClass) {
        return managedClass.getProperties(new Predicate<Property>() {
            public boolean apply(Property property) {
                return (typeMapper.isEntityReferenceOrValueObject(property));
            }
        });
    }

    private List<Property> getNonEmptyEnumerablesWithManagedClasses(final ObjectNode node) {
        return node.getManagedClass().getProperties(new Predicate<Property>() {
            public boolean apply(Property property) {
                JaversType javersType = typeMapper.getPropertyType(property);
                if (! (javersType instanceof EnumerableType)) {
                    return false;
                }
                EnumerableType enumerableType = (EnumerableType)javersType;

                Object container = node.getPropertyValue(property);
                if (enumerableType.isEmpty(container)) {
                    return false;
                }

                if (node.isNull(property)) {
                    return false;
                }
                return (typeMapper.isContainerOfManagedClasses(enumerableType) ||
                        typeMapper.isMapWithManagedClass(enumerableType)
                  );
            }
        });
    }

}