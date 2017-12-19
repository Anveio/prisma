package cool.graph.deploy.migration

import cool.graph.deploy.gc_value.GCStringConverter
import cool.graph.shared.models._
import org.scalactic.{Good, Or}
import sangria.ast.Document

trait NextProjectInferrer {
  def infer(baseProject: Project, graphQlSdl: Document): Project Or ProjectSyntaxError
}

sealed trait ProjectSyntaxError
case class RelationDirectiveNeeded(type1: String, type1Fields: Vector[String], type2: String, type2Fields: Vector[String]) extends ProjectSyntaxError

object NextProjectInferrer {
  def apply() = new NextProjectInferrer {
    override def infer(baseProject: Project, graphQlSdl: Document) = NextProjectInferrerImpl(baseProject, graphQlSdl).infer()
  }
}

case class NextProjectInferrerImpl(
    baseProject: Project,
    sdl: Document
) {
  import DataSchemaAstExtensions._

  def infer(): Project Or ProjectSyntaxError = {
    val newProject = Project(
      id = baseProject.id,
      ownerId = baseProject.ownerId,
      models = nextModels.toList,
      relations = nextRelations.toList,
      enums = nextEnums.toList
    )

    Good(newProject)
  }

  lazy val nextModels: Vector[Model] = {
    sdl.objectTypes.map { objectType =>
      val fields = objectType.fields.map { fieldDef =>
        val typeIdentifier = typeIdentifierForTypename(fieldDef.typeName)
        val relation       = fieldDef.relationName.flatMap(relationName => nextRelations.find(_.name == relationName))

        val wat = Field(
          id = fieldDef.name,
          name = fieldDef.name,
          typeIdentifier = typeIdentifier,
          isRequired = fieldDef.isRequired,
          isList = fieldDef.isList,
          isUnique = fieldDef.isUnique,
          enum = nextEnums.find(_.name == fieldDef.typeName),
          defaultValue = fieldDef.defaultValue.map(x => GCStringConverter(typeIdentifier, fieldDef.isList).toGCValue(x).get),
          relation = relation,
          relationSide = relation.map { relation =>
            if (relation.modelAId == objectType.name) {
              RelationSide.A
            } else {
              RelationSide.B
            }
          }
        )

        wat
      }

      val fieldNames            = fields.map(_.name)
      val missingReservedFields = ReservedFields.reservedFieldNames.filterNot(fieldNames.contains)
      val hiddenReservedFields  = missingReservedFields.map(ReservedFields.reservedFieldFor(_).copy(isHidden = true))

      Model(
        id = objectType.name,
        name = objectType.name,
        fields = fields.toList ++ hiddenReservedFields
      )
    }
  }

  lazy val nextRelations: Set[Relation] = {
    val tmp = for {
      objectType    <- sdl.objectTypes
      relationField <- objectType.relationFields
    } yield {
      Relation(
        id = relationField.relationName.get,
        name = relationField.relationName.get,
        modelAId = objectType.name,
        modelBId = relationField.typeName
      )
    }

    tmp.groupBy(_.name).values.flatMap(_.headOption).toSet
  }

  lazy val nextEnums: Vector[Enum] = {
    sdl.enumTypes.map { enumDef =>
      Enum(
        id = enumDef.name,
        name = enumDef.name,
        values = enumDef.values.map(_.name)
      )
    }
  }

  private def typeIdentifierForTypename(typeName: String): TypeIdentifier.Value = {
    if (sdl.objectType(typeName).isDefined) {
      TypeIdentifier.Relation
    } else if (sdl.enumType(typeName).isDefined) {
      TypeIdentifier.Enum
    } else {
      TypeIdentifier.withNameHacked(typeName)
    }
  }
}
